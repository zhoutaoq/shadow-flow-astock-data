package io.github.zhoutaoq.shadowflow.biz.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 单个账户的<b>导出 / 导入</b>（需求 v1.5.6 第 ③ 条）。
 *
 * <p>导出成一个 zip，里面三份 JSON，一份对一块，谁都看得懂：
 * <pre>
 * 账户名-账户导出-20260916.zip
 * ├── account.json     账户情况：名称 / 期初市值 / 可用金额 / 备注
 * ├── positions.json   持仓情况：代码 / 名称 / 成本价 / 持仓数量 / 可用数量 / 建仓日 / 当天买入池
 * └── records.json     操作记录：方向 / 标的 / 成交价 / 数量 / 成交额 / 当日已实现 / 成交日期 / 录入时刻
 * </pre>
 * <b>不带 id、不带 userId</b> —— 换个账号（甚至换台机器）导入照样能用，
 * 也不会把导出者的数据归属带过去。
 *
 * <h4>导入 = 新增一本账，绝不动已有的</h4>
 * 导入后的账户情况、持仓、操作记录原样回来（含「当天买入池」这类当日口径的字段），
 * 但<b>不复算</b>操作记录对持仓与可用金额的影响 —— 导出的持仓本身就是那些记录的结果，
 * 再算一遍就是重复计算。名称与已有账户撞名时自动加「（导入）」后缀。
 *
 * <p>校验一律「先全部验完再落库」：zip 结构、三份 JSON 的每个字段都检查过才建账户，
 * 这样脏文件最多换来一句报错，不会留下半本残账。
 */
@Service
public class AccountTransferService {

    private static final Logger log = LoggerFactory.getLogger(AccountTransferService.class);

    /** zip 体积上限（导出的文件只有几 KB，超过这个量级一定是拿错文件了） */
    private static final int MAX_ZIP_BYTES = 5 * 1024 * 1024;
    /** 单个 JSON 解压后的体积上限，防解压炸弹 */
    private static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;

    private static final String F_ACCOUNT = "account.json";
    private static final String F_POSITIONS = "positions.json";
    private static final String F_RECORDS = "records.json";

    private final AccountRepository repository;
    private final AccountService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public AccountTransferService(AccountRepository repository, AccountService service) {
        this.repository = repository;
        this.service = service;
    }

    /* ==================== 导出 ==================== */

    /**
     * 导出某个账户（账户情况 + 持仓情况 + 操作记录）为 zip 字节。
     *
     * <p>{@code getAccountRequired} 会挡住不属于当前用户的账户 id —— 导出接口不能变成
     * 「按 id 遍历别人的账」。
     */
    public byte[] exportZip(long userId, long accountId) {
        Account a = service.getAccountRequired(userId, accountId);
        List<Position> positions = repository.listPositions(userId, accountId);
        List<TradeRecord> records = repository.listRecords(userId, accountId);

        ObjectNode acct = mapper.createObjectNode();
        acct.put("name", a.getName());
        acct.put("initValue", nz(a.getInitValue()));
        acct.put("cash", nz(a.getCash()));
        acct.put("remark", a.getRemark());
        acct.put("dayRealizedDate", a.getDayRealizedDate());
        acct.put("dayRealizedProfit", nz(a.getDayRealizedProfit()));

        ArrayNode posArr = mapper.createArrayNode();
        for (Position p : positions) {
            ObjectNode n = mapper.createObjectNode();
            n.put("code", p.getCode());
            n.put("name", p.getName());
            n.put("costPrice", nz(p.getCostPrice()));
            n.put("quantity", p.getQuantity());
            n.put("availableQty", p.getAvailableQty());
            n.put("openDate", p.getOpenDate());
            n.put("dayBuyDate", p.getDayBuyDate());
            n.put("dayBuyQty", p.getDayBuyQty());
            n.put("dayBuyAmount", nz(p.getDayBuyAmount()));
            posArr.add(n);
        }

        ArrayNode recArr = mapper.createArrayNode();
        for (TradeRecord r : records) {
            ObjectNode n = mapper.createObjectNode();
            n.put("action", r.getAction());
            n.put("code", r.getCode());
            n.put("name", r.getName());
            n.put("price", nz(r.getPrice()));
            n.put("quantity", r.getQuantity());
            n.put("turnover", nz(r.getTurnover()));
            n.put("cashAfter", nz(r.getCashAfter()));
            n.put("sameDay", r.isSameDay());
            n.put("realizedProfit", nz(r.getRealizedProfit()));
            n.put("tradeDate", r.getTradeDate());
            n.put("time", r.getTime());
            recArr.add(n);
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            putJson(zos, F_ACCOUNT, acct);
            putJson(zos, F_POSITIONS, posArr);
            putJson(zos, F_RECORDS, recArr);
        } catch (IOException e) {
            throw new IllegalStateException("导出失败：" + e.getMessage(), e);
        }
        byte[] out = bos.toByteArray();
        log.info("[account] 用户 {} 导出账户 {}（{}）：{} 条持仓，{} 条操作记录，{} 字节",
                userId, accountId, a.getName(), positions.size(), records.size(), out.length);
        return out;
    }

    /** 导出文件名：账户名 + 日期，落在用户下载目录里一眼能认出来 */
    public String exportFileName(long userId, long accountId) {
        Account a = service.getAccountRequired(userId, accountId);
        String name = a.getName() == null || a.getName().trim().isEmpty() ? "账户" : a.getName().trim();
        return name + "-账户导出-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".zip";
    }

    private void putJson(ZipOutputStream zos, String name, JsonNode node) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
                .getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /* ==================== 导入 ==================== */

    /**
     * 导入一个导出的 zip：<b>新建一本账</b>，把账户情况、持仓、操作记录原样装进去。
     *
     * @return 新建的那本账（页面拿它跳进去看结果）
     */
    public Account importZip(long userId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("请选择要导入的文件");
        }
        if (bytes.length > MAX_ZIP_BYTES) {
            throw new IllegalArgumentException("文件太大了，账户导出文件只有几 KB，请确认是不是选错了文件");
        }
        Map<String, byte[]> entries = readZip(bytes);
        byte[] rawAccount = entries.get(F_ACCOUNT);
        if (rawAccount == null) {
            throw new IllegalArgumentException("这个文件里没有账户信息（" + F_ACCOUNT + "），不是本系统导出的账户文件");
        }

        JsonNode acc = parse(rawAccount, F_ACCOUNT);
        if (!acc.isObject()) {
            throw new IllegalArgumentException(F_ACCOUNT + " 的内容不是一份账户信息");
        }
        // ① 先全部验完：账户情况 + 持仓 + 操作记录，任何一处不对都不落库
        String name = text(acc.get("name"));
        BigDecimal init = decimal(acc.get("initValue"), "期初市值");
        BigDecimal cash = decimal(acc.get("cash"), "可用金额");
        if (init.signum() < 0) {
            throw new IllegalArgumentException("导入文件里的期初市值是负数，请确认文件是否被改过");
        }
        String remark = text(acc.get("remark"));
        String realizedDate = AccountService.checkYmd(text(acc.get("dayRealizedDate")), "当日已实现日期");
        BigDecimal realized = decimal(acc.get("dayRealizedProfit"), "当日已实现盈亏");

        List<Position> positions = readPositions(entries.get(F_POSITIONS));
        List<TradeRecord> records = readRecords(entries.get(F_RECORDS));

        // ② 落库：账户 → 持仓 → 记录
        Account created = service.createAccountForImport(userId, name, init, cash, remark);
        long aid = created.getId();
        if (realizedDate != null || realized.signum() != 0) {
            Account saved = service.getAccountRequired(userId, aid);
            saved.setDayRealizedDate(realizedDate);
            saved.setDayRealizedProfit(realized);
            repository.saveAccount(saved);
        }
        for (Position p : positions) {
            p.setId(null);
            p.setUserId(userId);
            p.setAccountId(aid);
            repository.savePosition(p);
        }
        for (TradeRecord r : records) {
            r.setId(0);
            r.setUserId(userId);
            r.setAccountId(aid);
            repository.saveRecord(r);
        }
        repository.trimRecords(userId, aid, AccountService.MAX_RECORDS);
        // 导入进来的持仓也进盯盘：账户页看到的就是「我持有这些标的」
        log.info("[account] 用户 {} 导入账户「{}」（id={}）：{} 条持仓，{} 条操作记录",
                userId, created.getName(), aid, positions.size(), records.size());
        return created;
    }

    /** 读 zip 里的条目：只按文件名取，不落磁盘（也就没有路径穿越这回事） */
    private Map<String, byte[]> readZip(byte[] bytes) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String key = e.getName();
                int slash = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
                if (slash >= 0) {
                    key = key.substring(slash + 1);
                }
                byte[] data = readEntry(zis);
                // 打包工具可能带上 __MACOSX 之类的影子条目，同名只认第一个
                if (!out.containsKey(key)) {
                    out.put(key, data);
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("文件不是有效的 zip 压缩包");
        }
        return out;
    }

    private byte[] readEntry(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = zis.read(buf)) > 0) {
            total += n;
            if (total > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("压缩包里的文件过大，请确认是不是本系统导出的账户文件");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private JsonNode parse(byte[] raw, String label) {
        try {
            JsonNode n = mapper.readTree(new String(raw, StandardCharsets.UTF_8));
            if (n == null || n.isMissingNode() || n.isNull()) {
                throw new IllegalArgumentException(label + " 是空的");
            }
            return n;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalArgumentException(label + " 不是有效的 JSON：" + e.getMessage());
        }
    }

    /** 持仓情况：缺这一份当「没有持仓」；有就逐条验 */
    private List<Position> readPositions(byte[] raw) {
        List<Position> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        JsonNode node = parse(raw, F_POSITIONS);
        if (!node.isArray()) {
            throw new IllegalArgumentException(F_POSITIONS + " 的内容不是一个列表");
        }
        if (node.size() > AccountService.MAX_POSITIONS) {
            throw new IllegalArgumentException("导入的持仓超过 " + AccountService.MAX_POSITIONS + " 条，请拆分后再导");
        }
        Set<String> codes = new HashSet<>();
        for (JsonNode n : node) {
            String code = AccountService.normalizeCode(text(n.get("code")));
            if (code == null) {
                throw new IllegalArgumentException("持仓里的标的代码「" + text(n.get("code")) + "」不是 6 位股票代码");
            }
            if (!codes.add(code)) {
                throw new IllegalArgumentException("导入文件里 " + code + " 有重复的持仓记录，请先整理后再导");
            }
            BigDecimal cost = decimal(n.get("costPrice"), code + " 的成本价");
            if (cost.signum() <= 0) {
                throw new IllegalArgumentException(code + " 的成本价必须大于 0");
            }
            int qty = intOf(n.get("quantity"), code + " 的持仓数量");
            if (qty <= 0) {
                throw new IllegalArgumentException(code + " 的持仓数量必须大于 0");
            }
            int available = intOf(n.get("availableQty"), code + " 的可用数量");
            if (n.get("availableQty") == null || n.get("availableQty").isNull()) {
                available = qty;   // 老文件没这个字段 = 全部可用
            }
            if (available < 0 || available > qty) {
                throw new IllegalArgumentException(code + " 的可用数量超出持仓数量，请确认文件是否被改过");
            }
            Position p = new Position();
            p.setCode(code);
            String pname = text(n.get("name"));
            p.setName(pname == null || pname.isEmpty() ? code : pname);
            p.setCostPrice(cost);
            p.setQuantity(qty);
            p.setAvailableQty(available);
            p.setOpenDate(AccountService.checkYmd(text(n.get("openDate")), code + " 的建仓日"));
            p.setDayBuyDate(AccountService.checkYmd(text(n.get("dayBuyDate")), code + " 的当天买入日期"));
            p.setDayBuyQty(intOf(n.get("dayBuyQty"), code + " 的当天买入数量"));
            p.setDayBuyAmount(decimal(n.get("dayBuyAmount"), code + " 的当天买入金额"));
            out.add(p);
        }
        return out;
    }

    /** 操作记录：缺这一份当「没有记录」；有就逐条验（只留最新的若干条，与在线记账同一上限） */
    private List<TradeRecord> readRecords(byte[] raw) {
        List<TradeRecord> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        JsonNode node = parse(raw, F_RECORDS);
        if (!node.isArray()) {
            throw new IllegalArgumentException(F_RECORDS + " 的内容不是一个列表");
        }
        for (JsonNode n : node) {
            String action = text(n.get("action"));
            if (!TradeRecord.ACTION_BUY.equalsIgnoreCase(action) && !TradeRecord.ACTION_SELL.equalsIgnoreCase(action)) {
                throw new IllegalArgumentException("操作记录里的方向「" + action + "」只能是买入或卖出");
            }
            String code = AccountService.normalizeCode(text(n.get("code")));
            if (code == null) {
                throw new IllegalArgumentException("操作记录里的标的代码「" + text(n.get("code")) + "」不是 6 位股票代码");
            }
            int qty = intOf(n.get("quantity"), code + " 的数量");
            if (qty <= 0) {
                throw new IllegalArgumentException(code + " 的操作数量必须大于 0");
            }
            TradeRecord r = new TradeRecord();
            r.setAction(TradeRecord.ACTION_BUY.equalsIgnoreCase(action) ? TradeRecord.ACTION_BUY
                    : TradeRecord.ACTION_SELL);
            r.setCode(code);
            r.setName(text(n.get("name")));
            r.setPrice(decimal(n.get("price"), code + " 的成交价"));
            r.setQuantity(qty);
            r.setTurnover(decimal(n.get("turnover"), code + " 的成交额"));
            r.setCashAfter(decimal(n.get("cashAfter"), code + " 的操作后可用金额"));
            r.setSameDay(n.get("sameDay") == null || n.get("sameDay").asBoolean(true));
            r.setRealizedProfit(decimal(n.get("realizedProfit"), code + " 的当日已实现盈亏"));
            r.setTradeDate(AccountService.checkYmd(text(n.get("tradeDate")), code + " 的成交日期"));
            r.setTime(text(n.get("time")));
            out.add(r);
        }
        if (out.size() > AccountService.MAX_RECORDS) {
            // 记录是只增的流水，超出上限时保留最新的那批（列表本来就是「新的在前」）
            return new ArrayList<>(out.subList(out.size() - AccountService.MAX_RECORDS, out.size()));
        }
        return out;
    }

    /* ==================== 内部：字段解析 ==================== */

    private static String text(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return s == null ? null : s.trim();
    }

    private static BigDecimal decimal(JsonNode n, String label) {
        if (n == null || n.isNull()) {
            return BigDecimal.ZERO;
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        String s = text(n);
        if (s == null || s.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "「" + s + "」不是数字");
        }
    }

    private static int intOf(JsonNode n, String label) {
        if (n == null || n.isNull()) {
            return 0;
        }
        if (n.isNumber()) {
            return n.asInt();
        }
        String s = text(n);
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return new BigDecimal(s).intValue();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "「" + s + "」不是数字");
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
