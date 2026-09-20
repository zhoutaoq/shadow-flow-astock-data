/*
 * v1.6.0 静态体检 + 系统配置页提示气泡（jsdom，不需要浏览器、不需要起服务）
 *
 * 覆盖本次重构的三类硬约束：
 *   1. 品牌与口径 —— 产品可见面不得再出现旧品牌名，也不得出现任何成本/来源类表述；
 *   2. 存储命名 —— schema.sql 建的表必须全是 shadowflow_ 前缀；
 *   3. 提示文案规范 —— 说明只走 `?` 气泡：默认不可见、可聚焦、气泡内容非空，
 *      系统配置页不得再有常驻的成段说明。
 *
 * 准备（一次性，jsdom 装在隔离工作区）：
 *   cd %USERPROFILE%\.workbuddy\binaries\node\workspace && npm install jsdom
 * 运行（项目根目录）：
 *   NODE_PATH=%USERPROFILE%\.workbuddy\binaries\node\workspace\node_modules \
 *     node scripts/check_v16.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const STATIC = path.join(ROOT, 'shadow-flow-biz/src/main/resources/static');
const BIZ_SRC = path.join(ROOT, 'shadow-flow-biz/src');
const NOTIFY_SRC = path.join(ROOT, 'shadow-flow-notify/src');

let failed = 0;
function ok(cond, name, extra) {
    console.log((cond ? '  PASS  ' : '  FAIL  ') + name + (extra ? '  → ' + extra : ''));
    if (!cond) failed++;
}
function read(p) { return fs.readFileSync(p, 'utf8'); }
function walk(dir, exts, out) {
    out = out || [];
    for (const n of fs.readdirSync(dir)) {
        const p = path.join(dir, n);
        const st = fs.statSync(p);
        if (st.isDirectory()) {
            if (n === 'target' || n === 'node_modules' || n === '.git') continue;
            walk(p, exts, out);
        } else if (exts.has(path.extname(n).toLowerCase())) {
            out.push(p);
        }
    }
    return out;
}
const rel = p => path.relative(ROOT, p).replace(/\\/g, '/');

/* =================== 1. 品牌与口径 =================== */
console.log('== 1. 品牌与口径（产品可见面） ==');

const VISIBLE_DIRS = [STATIC, BIZ_SRC, NOTIFY_SRC, path.join(ROOT, 'sh')];
const VISIBLE_FILES = [path.join(ROOT, 'README.md'), path.join(ROOT, 'pom.xml')];
const CODE_EXT = new Set(['.java', '.js', '.html', '.css', '.yml', '.yaml', '.sql', '.md', '.bat', '.env', '.sh', '.txt']);

const visible = [];
for (const d of VISIBLE_DIRS) {
    if (fs.existsSync(d)) walk(d, CODE_EXT, visible);
}
for (const f of VISIBLE_FILES) if (fs.existsSync(f)) visible.push(f);

function scan(words) {
    const hits = [];
    for (const f of visible) {
        const lines = read(f).split('\n');
        lines.forEach((line, i) => {
            for (const w of words) {
                if (line.includes(w)) hits.push(`${rel(f)}:${i + 1}  ${w}  ${line.trim().slice(0, 90)}`);
            }
        });
    }
    return hits;
}

const brandHits = scan(['金牛数据']);
ok(brandHits.length === 0, '产品可见面无旧品牌「金牛数据」', brandHits.slice(0, 5).join('\n         '));

const costHits = scan(['计费', '供应商', '花钱', '额度', '免费', '收费', '付费', '歪枣']);
ok(costHits.length === 0, '产品可见面无成本/来源类表述', costHits.slice(0, 8).join('\n         '));

const oldFn = scan(['confirmApiCost', 'CONFIRM_CALLS_OVER', '计费保护']);
ok(oldFn.length === 0, '旧函数名 confirmApiCost / 常量 CONFIRM_CALLS_OVER 已全部替换',
    oldFn.slice(0, 5).join('\n         '));

const brand = read(path.join(STATIC, 'index.html'));
ok(brand.indexOf('<title>影流数据</title>') >= 0, '首页标题已改为「影流数据」');

/* =================== 2. 存储命名 =================== */
console.log('== 2. 存储命名（schema.sql） ==');

const schema = read(path.join(ROOT, 'shadow-flow-biz/src/main/resources/schema.sql'));
const tables = [...schema.matchAll(/CREATE TABLE IF NOT EXISTS\s+([a-z_]+)/g)].map(m => m[1]);
ok(tables.length >= 11, `识别到 ${tables.length} 张建表语句`);
ok(tables.every(t => t.startsWith('shadowflow_')), '建表全部为 shadowflow_ 前缀',
    tables.filter(t => !t.startsWith('shadowflow_')).join(','));
ok(!/waizao_/.test(schema.replace(/^.*旧表由启动钩子.*$/gm, '')), 'schema.sql 正文无 waizao_ 残留');
ok(!/(^|\s)(uk|idx)_waizao_/.test(schema), 'schema.sql 索引名无 waizao 前缀');

// 仓储里的表名常量必须都是新前缀。两个刻意的例外：
//   · DbSysConfigRepository 读更早的旧表 waizao_api_config（v1.3 之前的遗留库兜底）；
//   · ShadowFlowDbInitializer 的旧表清单 —— 它的全部职责就是认识旧名字，认不出就没法改名。
const repoFiles = walk(path.join(ROOT, 'shadow-flow-biz/src/main/java'), new Set(['.java']));
const TABLE_NAME_EXEMPT = new Set(['ShadowFlowDbInitializer.java', 'DbSysConfigRepository.java']);
const badTable = [];
for (const f of repoFiles) {
    if (TABLE_NAME_EXEMPT.has(path.basename(f))) continue;
    const src = read(f);
    for (const m of src.matchAll(/"((?:shadowflow|waizao)_[a-z_]+)"/g)) {
        const name = m[1];
        if (name.startsWith('shadowflow_')) continue;
        badTable.push(rel(f) + '  ' + name);
    }
}
ok(badTable.length === 0, 'Java 里无「旧前缀表名」硬编码（排除迁移钩子与旧表兜底读取）',
    badTable.slice(0, 6).join('\n         '));

ok(/class ShadowFlowDbInitializer/.test(read(path.join(ROOT,
    'shadow-flow-biz/src/main/java/io/github/zhoutaoq/shadowflow/biz/config/ShadowFlowDbInitializer.java'))),
    '存在数据库启动钩子 ShadowFlowDbInitializer');
ok(!/spring:\s*\n\s*sql:[\s\S]{0,200}?mode:\s*always/.test(read(path.join(ROOT,
    'shadow-flow-biz/src/main/resources/application.yml'))),
    'application.yml 已关掉 spring.sql.init 自动建表（避免抢在改名之前建出新表）');

/* =================== 3. 提示文案规范 =================== */
console.log('== 3. 提示文案规范（? 气泡） ==');

const css = read(path.join(STATIC, 'css/common.css'));
ok(/\.hint-q\s*\{/.test(css), 'common.css 有 .hint-q');
ok(/\.hint-box\s*\{[\s\S]{0,900}?visibility:\s*hidden/.test(css), '.hint-box 默认 visibility: hidden');
ok(/\.hint-q:hover \.hint-box/.test(css), '悬浮展开选择器存在');
ok(/\.hint-q:focus \.hint-box/.test(css) && /\.hint-q:focus-within \.hint-box/.test(css),
    '键盘 focus / focus-within 展开选择器存在');

// 每个 hint-q 都必须可聚焦（移动端靠 tabindex 点按）且内含 hint-box
const staticFiles = walk(STATIC, new Set(['.html', '.js']));
let qCount = 0, boxCount = 0, badQ = [];
for (const f of staticFiles) {
    const src = read(f);
    const qs = [...src.matchAll(/class="hint-q[^"]*"[^>]*>/g)];
    qCount += qs.length;
    boxCount += (src.match(/class="hint-box"/g) || []).length;
    for (const m of qs) {
        if (!/tabindex="0"/.test(m[0])) badQ.push(rel(f) + '  ' + m[0]);
    }
}
ok(qCount > 0, `静态资源里共 ${qCount} 个 ? 气泡`);
ok(badQ.length === 0, '每个 ? 都带 tabindex="0"（可点按/可键盘聚焦）', badQ.join('\n         '));
ok(boxCount === qCount, `hint-box 数量与 hint-q 一致（${boxCount} / ${qCount}）`);

const sysHtml = read(path.join(STATIC, 'pages/system.html'));
ok(!/class="ig-hint"/.test(sysHtml), '系统配置页不再有常驻的 .ig-hint 说明行');
ok(!/class="sp-hint">\s*[^<]*全站的股票名称/.test(sysHtml), '「股票名称跳转」的长说明已收进气泡');
ok(!/class="sp-hint">\s*[\s\S]{0,40}平台自己/.test(sysHtml), '「第三方对接」的长说明已收进气泡');

const watchHtml = read(path.join(STATIC, 'pages/watch.html'));
ok(!/class="push-hint"/.test(watchHtml), '盯盘页不再有常驻的 .push-hint 说明块');
ok(/placeholder="留空则推送到平台默认群"/.test(watchHtml), '关键限制仍留在 placeholder（没被塞进气泡）');

/* =================== 4. jsdom 渲染真实断言 =================== */
console.log('== 4. 系统配置页渲染（jsdom） ==');

let JSDOM;
try {
    JSDOM = require('jsdom').JSDOM;
} catch (e) {
    console.log('  SKIP  未找到 jsdom，跳过渲染断言（装法见本文件头注释）');
    console.log('\n' + (failed === 0 ? '静态体检全部通过（渲染断言已跳过）' : failed + ' 项失败'));
    process.exit(failed === 0 ? 0 : 1);
}

const INTEGRATION_FIXTURE = {
    items: [
        {name: 'dataApiToken', label: '数据接口令牌', hint: '行情数据服务的访问凭据，页面只回显前后各 4 位。',
            secret: true, configured: true, source: 'config', display: '305c****070c'},
        {name: 'dingtalkWebhook', label: '系统通知机器人', hint: '平台自己发通知的出口。',
            secret: true, configured: true, source: 'yml', display: 'https****3317'},
        {name: 'feedbackKeyword', label: '反馈消息关键词', hint: '钉钉自定义关键词模式下必须命中的词。',
            secret: false, configured: true, source: 'yml', display: '智能盯盘'}
    ],
    overridden: 1, total: 3, feedbackFallbackToDingtalk: true
};

function fixtureFor(url) {
    if (url.indexOf('/api/mode') === 0) {
        return {success: true, message: 'ok', data: {dbEnabled: false}};
    }
    if (url.indexOf('/api/auth/me') === 0) {
        return {success: true, message: 'ok', data: {loggedIn: true, user: {username: 'admin', admin: true},
            registrationEnabled: true}};
    }
    if (url.indexOf('/api/home/hero') === 0) {
        return {success: true, message: 'ok', data: {code: '000001', name: '平安银行', startDate: '2026-01-01',
            endDate: '2026-06-30', bars: [[0, 0, 0, 10], [0, 0, 0, 11]], configured: true, updateTime: '2026-09-20 12:00:00'}};
    }
    if (url.indexOf('/api/stock-link') === 0) {
        return {success: true, message: 'ok', data: {enabled: true, defaultTarget: 'chart',
            targets: [{key: 'chart', label: '本系统 K 线查看', enabled: true, external: false, url: '/pages/chart.html?code={code}'}]}};
    }
    if (url.indexOf('/api/admin/integration') === 0) {
        return {success: true, message: 'ok', data: INTEGRATION_FIXTURE};
    }
    return {success: true, message: 'ok', data: {}};
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

(async function main() {
    const dom = new JSDOM('<!DOCTYPE html><html><body><div id="msgBox"></div></body></html>',
        {runScripts: 'dangerously', url: 'http://127.0.0.1:8099/'});
    const win = dom.window;

    // 把 common.css 注入成 <style>，这样 getComputedStyle 能算出气泡的默认可见性
    const style = win.document.createElement('style');
    style.textContent = css;
    win.document.head.appendChild(style);

    const calls = [];
    win.fetch = async (url) => {
        calls.push(url);
        return {status: 200, json: async () => fixtureFor(url)};
    };

    function inject(code) {
        const s = win.document.createElement('script');
        s.textContent = code;
        win.document.body.appendChild(s);
    }
    inject(read(path.join(STATIC, 'js/common.js')));
    inject(read(path.join(STATIC, 'js/date-picker.js')));
    inject(read(path.join(STATIC, 'js/stock-picker.js')));

    // 把页面 <body> 里除脚本之外的内容搬进 jsdom，再跑页面自己的内联脚本
    const bodyInner = sysHtml.replace(/[\s\S]*?<body>/, '').replace(/<\/body>[\s\S]*$/, '')
        .replace(/<script\b[^>]*\bsrc=[^>]*>\s*<\/script>/g, '');
    const holder = win.document.createElement('div');
    holder.innerHTML = bodyInner;
    win.document.body.appendChild(holder);

    const inline = [...sysHtml.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
        .filter(m => !/\bsrc\s*=/.test(m[1]))
        .map(m => m[2])
        .join('\n');
    inject(inline);

    await sleep(400);

    ok(calls.some(u => u.indexOf('/api/admin/integration') === 0), '页面确实拉了第三方对接配置',
        calls.join(' | '));

    const igRows = win.document.querySelectorAll('#igList .ig-row');
    ok(igRows.length === 3, `对接项渲染出 3 行`, '实测 ' + igRows.length);

    const firstQ = win.document.querySelector('#igList .ig-hd .hint-q');
    ok(!!firstQ, '对接项标题旁有 ? 气泡');
    ok(firstQ && firstQ.getAttribute('tabindex') === '0', '? 可聚焦（tabindex=0）');
    const firstBox = firstQ && firstQ.querySelector('.hint-box');
    ok(!!firstBox, '? 内含 .hint-box');
    ok(firstBox && firstBox.textContent.trim().length > 4, '气泡内有说明正文（不是空壳）',
        firstBox ? firstBox.textContent.trim().slice(0, 40) : '');
    ok(!win.document.querySelector('#igList .ig-hint'), '对接项不再渲染常驻说明行');

    const vis = firstBox ? win.getComputedStyle(firstBox).visibility : '';
    ok(vis === 'hidden', '气泡默认不可见（getComputedStyle.visibility=hidden）', '实测 ' + vis);

    const h2Q = win.document.querySelectorAll('.panel h2 .hint-q');
    ok(h2Q.length >= 2, `面板标题上有 ${h2Q.length} 个 ? 气泡（股票名称跳转 / 第三方对接）`);

    // 默认态「看不到说明」的判据只能靠计算样式 —— textContent 会把隐藏气泡里的字也算进去
    const allBoxes = [...win.document.querySelectorAll('.hint-box')];
    const visibleBoxes = allBoxes.filter(b => win.getComputedStyle(b).visibility !== 'hidden');
    ok(allBoxes.length >= 2 && visibleBoxes.length === 0,
        `全部 ${allBoxes.length} 个气泡默认都不可见`, '可见的 ' + visibleBoxes.length + ' 个');

    dom.window.close();

    console.log('');
    console.log(failed === 0 ? '全部通过' : failed + ' 项失败');
    process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
    console.error('渲染断言异常:', e);
    process.exit(1);
});
