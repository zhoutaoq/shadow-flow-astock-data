package io.github.zhoutaoq.shadowflow.biz.service;

import io.github.zhoutaoq.shadowflow.biz.store.DataDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 系统 logo 的读取与更新（需求 v1.6.2 第 2 条）。
 *
 * <p><b>两份 logo 的关系</b>：
 * <ul>
 *   <li><b>出厂 logo</b> —— {@code classpath:/static/img/logo.jpg}，随包发布，永远在；</li>
 *   <li><b>自定义 logo</b> —— 超管在「系统配置」页上传的那份，落在运行期目录
 *       {@code <data-dir>/logo/logo.<ext>}，<b>改了立刻生效、不用重新打包</b>。</li>
 * </ul>
 * 读取时自定义优先，没有就用出厂的。之所以不往 {@code static/} 里写：那是打包进 jar 的资源，
 * 运行期根本改不动；而无数据库模式下 {@code data/} 本来就是这个服务唯一可写的运行期目录。
 *
 * <p><b>不做图形处理</b>：只校验类型与体积，原样落盘。logo 是个几十 KB 的图片，
 * 替用户重编码一遍（缩放、转格式）只会引入「传上去糊了」这类说不清的问题，
 * 要什么尺寸让设计侧自己出。
 *
 * <p>{@link #current()} 返回的是<b>相对 URL</b>（{@code /api/logo/view?v=<时间戳>}）而不是绝对地址：
 * 同一个部署在内网、外网、反代后面访问的主机名可能都不一样，服务端不可能替它们各自拼一个。
 */
@Service
public class LogoService {

    private static final Logger log = LoggerFactory.getLogger(LogoService.class);

    /** 出厂 logo 的相对 URL（打包进 jar，走静态资源通道） */
    public static final String DEFAULT_URL = "/img/logo.jpg";

    /** 自定义 logo 的对外 URL；带 v 参数是为了换图后浏览器缓存立刻失效 */
    public static final String CUSTOM_URL = "/api/logo/view";

    /** 上传体积上限：logo 用不着大图，超过基本是传错了文件 */
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    /** 允许的图片类型 → 落盘扩展名 */
    private static final String[][] ALLOWED = {
            {"image/jpeg", "jpg"},
            {"image/jpg", "jpg"},
            {"image/png", "png"},
            {"image/webp", "webp"},
            {"image/gif", "gif"}
    };

    /** 自定义 logo 所在子目录（相对 data-dir） */
    private static final String SUB_DIR = "logo";
    /** 落盘文件名（不带扩展名）：同一时刻只留一份，换图就是覆盖 */
    private static final String FILE_BASE = "logo";

    private final DataDir dataDir;

    public LogoService(DataDir dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * 当前生效的 logo 地址。
     *
     * <p>自定义那份存在时带上 {@code v=<最后修改时间>} —— 换图后 URL 就变了，
     * 浏览器不会再拿旧图（顶栏 logo 是每个页面都要加载的资源，缓存必须能失效）。
     */
    public String currentUrl() {
        Path custom = findCustom();
        if (custom == null) {
            return DEFAULT_URL;
        }
        try {
            long v = Files.getLastModifiedTime(custom).toMillis();
            return CUSTOM_URL + "?v=" + v;
        } catch (IOException e) {
            return CUSTOM_URL;
        }
    }

    /** 是否用的是自定义 logo（false = 出厂那份） */
    public boolean customized() {
        return findCustom() != null;
    }

    /** 自定义 logo 的落盘路径；没有自定义时返回 null */
    public Path findCustom() {
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        for (String[] type : ALLOWED) {
            Path p = dir.resolve(FILE_BASE + "." + type[1]);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 保存上传的 logo：先写临时文件再原子改名，避免上传到一半时页面读到半张图。
     *
     * <p>落盘前先把旧的那份删掉 —— 换了扩展名（jpg → png）时旧文件不会自动消失，
     * 留着会让 {@link #findCustom()} 按 {@link #ALLOWED} 的顺序取到老的。
     *
     * @throws IllegalArgumentException 文件为空 / 类型不支持 / 超过体积上限
     */
    public String save(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请先选择一张图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("图片不能超过 " + (MAX_BYTES / 1024 / 1024) + " MB，"
                    + "当前 " + (file.getSize() / 1024) + " KB");
        }
        String ext = extOf(file);
        Path dir = dir();
        Path target = dir.resolve(FILE_BASE + "." + ext);
        Path tmp = dir.resolve(FILE_BASE + "." + ext + ".tmp");
        try {
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), tmp, StandardCopyOption.REPLACE_EXISTING);
            // 先清掉其它扩展名的旧图，再改名（此时旧图已被删，不存在两份并存）
            removeOthers(target);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(tmp);
            log.warn("[logo] 保存失败：{}", e.getMessage());
            throw new IllegalArgumentException("图片保存失败：" + e.getMessage());
        }
        log.info("[logo] 系统 logo 已更新：{}（{} KB）", target.getFileName(), file.getSize() / 1024);
        return currentUrl();
    }

    /** 恢复出厂：删掉自定义 logo，回到打包里的那份 */
    public String reset() {
        removeOthers(null);
        log.info("[logo] 系统 logo 已恢复出厂");
        return currentUrl();
    }

    private Path dir() {
        return dataDir.root().resolve(SUB_DIR);
    }

    /** 删掉除 keep 之外的所有 logo 文件 */
    private void removeOthers(Path keep) {
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        for (String[] type : ALLOWED) {
            Path p = dir.resolve(FILE_BASE + "." + type[1]);
            if (keep != null && p.equals(keep)) {
                continue;
            }
            deleteQuietly(p);
        }
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.debug("[logo] 删除 {} 失败：{}", p, e.getMessage());
        }
    }

    /** 从 content-type 判类型，拿不到时退回按文件名后缀判；都不认就拒绝 */
    private String extOf(MultipartFile file) {
        String ct = file.getContentType();
        if (StringUtils.hasText(ct)) {
            String c = ct.trim().toLowerCase();
            for (String[] type : ALLOWED) {
                if (type[0].equals(c)) {
                    return type[1];
                }
            }
        }
        String name = file.getOriginalFilename();
        if (StringUtils.hasText(name)) {
            String n = name.toLowerCase();
            for (String[] type : ALLOWED) {
                if (n.endsWith("." + type[1])) {
                    return type[1];
                }
            }
            if (n.endsWith(".jpeg")) {
                return "jpg";
            }
        }
        throw new IllegalArgumentException("只支持 JPG / PNG / WEBP / GIF 图片");
    }
}
