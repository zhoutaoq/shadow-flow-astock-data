/*
 * v1.6.1 环境变量查看（jsdom，不需要浏览器、不需要起服务）
 *
 * 覆盖 releasev1.6.1 的三条硬约束：
 *   1. 只读 —— 弹窗里没有任何输入控件，也不回写接口；
 *   2. 脱敏 —— 页面文本里不得出现凭据明文（接口给了打码值，前端不得再拼原文）；
 *   3. 来源可辨 —— 系统配置页 / 环境变量 / 未设置 三种来源都要能标出来。
 *
 * 准备（一次性，jsdom 装在隔离工作区）：
 *   cd %USERPROFILE%\.workbuddy\binaries\node\workspace && npm install jsdom
 * 运行（项目根目录）：
 *   NODE_PATH=%USERPROFILE%\.workbuddy\binaries\node\workspace\node_modules \
 *     node scripts/check_v161.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const STATIC = path.join(ROOT, 'shadow-flow-biz/src/main/resources/static');

let failed = 0;
function ok(cond, name, extra) {
    console.log((cond ? '  PASS  ' : '  FAIL  ') + name + (extra ? '  → ' + extra : ''));
    if (!cond) failed++;
}
function read(p) { return fs.readFileSync(p, 'utf8'); }
const sleep = ms => new Promise(r => setTimeout(r, ms));

/* =================== 1. 静态：密钥不得再出现在仓库文件里 =================== */
console.log('== 1. 仓库内无明文密钥 ==');

/*
 * 明文探针本身也不能写进仓库 —— 把真实令牌抄进校验脚本，等于换个文件继续泄露。
 * 取值顺序：进程环境变量 -> 本机未入库的 app.env（src/main/resources 下）。
 * 取不到就跳过该类探针（打印 SKIP），绝不留明文兜底值。
 * 本地要跑全量检查，先把 app.env 填成真实值（它已在 .gitignore 里）。
 */
function loadAppEnv() {
    const file = path.join(ROOT, 'shadow-flow-biz/src/main/resources/app.env');
    const map = {};
    if (!fs.existsSync(file)) return map;
    for (const raw of read(file).split(/\r?\n/)) {
        const line = raw.trim();
        if (!line || /^(REM\b|#|@echo)/i.test(line)) continue;
        // 键名限定为标识符：否则贪婪的 [^"]+ 会吃到 URL 里最后一个 '='，把整串当成键
        const m = line.match(/^set\s+"([A-Za-z_][\w.]*)=(.*)"\s*$/) || line.match(/^([A-Za-z_][\w.]*)=(.*)$/);
        if (m) map[m[1]] = m[2];
    }
    return map;
}
const LOCAL_ENV = loadAppEnv();
// 「在此填写…」这类模板占位文字不算凭据，别拿它当探针去扫
const PLACEHOLDER = /^(在此填写|your[_-]|changeme|placeholder|<.*>)/i;
function probe(name) {
    const v = (process.env[name] || LOCAL_ENV[name] || '').trim();
    return v.length >= 6 && !PLACEHOLDER.test(v) ? v : '';
}
// webhook 整串 URL 受域名/参数顺序影响，只取 access_token 这一段做探针
function webhookToken(url) {
    const m = /\baccess_token=([0-9a-zA-Z_-]{16,})/.exec(url || '');
    return m ? m[1] : '';
}

const PLAINS = [
    ['行情令牌', probe('SHADOWFLOW_API_TOKEN')],
    ['钉钉 webhook access_token', webhookToken(probe('DINGTALK_WEBHOOK'))],
    ['数据库口令', probe('DB_PASSWORD')]
].filter(pair => pair[1]);
// 允许出现的地方：app.env（不入库，本机/服务器上的真实配置）。
// 另外排除运行期目录（data/ 等，.gitignore 里已忽略、不进包）：它们装的是本机跑出来的
// 配置快照，明文只存在于这台机器的磁盘上，与「仓库里有没有硬编码密钥」无关。
const ALLOW = new Set(['app.env']);

const SKIP_DIRS = ['target', 'node_modules', '.git', '.build', 'data', 'logs'];
function walk(dir, out) {
    out = out || [];
    for (const n of fs.readdirSync(dir)) {
        const p = path.join(dir, n);
        const st = fs.statSync(p);
        if (st.isDirectory()) {
            if (SKIP_DIRS.includes(n)) continue;
            walk(p, out);
        } else {
            out.push(p);
        }
    }
    return out;
}
// 只扫「会进包 / 会进进程」的那几类文件。文档（md）不扫：
// docs/design 里的需求与方案记录按惯例会引用「改造前的值」，那是叙述历史，不是泄露当前凭据。
const hits = [];
if (PLAINS.length === 0) {
    console.log('  SKIP  本机没有可用探针（app.env 未填 / 环境变量未设），跳过全仓密钥扫描');
} else {
    for (const f of walk(ROOT)) {
        const base = path.basename(f);
        if (ALLOW.has(base)) continue;
        if (!/\.(java|yml|yaml|properties|xml|html|js|css|bat|sh|env|example|json|txt)$/i.test(f)) continue;
        const src = read(f);
        for (const [label, plain] of PLAINS) {
            if (src.includes(plain)) hits.push(path.relative(ROOT, f).replace(/\\/g, '/') + '  ' + label);
        }
    }
    ok(hits.length === 0, `全仓库（除 app.env 与文档示例）无明文密钥，探针 ${PLAINS.length} 条`,
        hits.join('\n         '));
}

const yml = read(path.join(ROOT, 'shadow-flow-biz/src/main/resources/application.yml'));
['DB_PASSWORD', 'ADMIN_PASSWORD', 'SHADOWFLOW_API_TOKEN', 'DINGTALK_WEBHOOK'].forEach(k => {
    ok(new RegExp('\\$\\{' + k + ':\\}').test(yml), `application.yml 里 ${k} 是空兜底占位符`);
});

/* =================== 2. jsdom：环境变量弹窗 =================== */
console.log('== 2. 环境变量查看弹窗（jsdom） ==');

let JSDOM;
try {
    JSDOM = require('jsdom').JSDOM;
} catch (e) {
    console.log('  SKIP  未找到 jsdom，跳过渲染断言（装法见本文件头注释）');
    console.log('\n' + (failed === 0 ? '静态检查全部通过（渲染断言已跳过）' : failed + ' 项失败'));
    process.exit(failed === 0 ? 0 : 1);
}

/** 明文只出现在 fixture 里当「探测器」：页面若把它渲染出来就是脱敏失效。
 *  display 用的是纯占位打码串（aaaa****bbbb），不再借用真实凭据的首尾字符。 */
const SECRETS_FIXTURE = [
    {name: 'DB_PASSWORD', label: '数据库口令', hint: '仅数据库模式用。', secret: true,
        configured: true, source: '环境变量', display: 'aaaa****bbbb'},
    {name: 'ADMIN_PASSWORD', label: '超管初始口令', hint: '只在 admin 账号不存在时用。', secret: true,
        configured: true, source: '环境变量', display: '****'},
    {name: 'SHADOWFLOW_API_TOKEN', label: '行情数据服务令牌', hint: '优先取配置页。', secret: true,
        configured: true, source: '系统配置页', display: 'cccc****dddd'},
    {name: 'DINGTALK_WEBHOOK', label: '系统通知机器人 Webhook', hint: '平台通知出口。', secret: true,
        configured: false, source: '未设置', display: ''}
];
const RUNTIMES_FIXTURE = [
    {name: 'REMOTE_DIR', label: '部署目录', hint: '部署目录。', secret: false, configured: true, value: '/home/stock-backtesting'},
    {name: 'JAR_NAME', label: 'jar 基名', hint: 'jar 基名。', secret: false, configured: true, value: 'shadow-flow-biz'},
    {name: 'JAVA', label: '远端 JDK', hint: '远端 JDK。', secret: false, configured: true, value: '/usr/bin/java'},
    {name: 'OPTS', label: 'JVM 参数', hint: 'JVM 参数。', secret: false, configured: true, value: '-Xms256m -Xmx768m'},
    {name: 'ARGS', label: '启动参数', hint: '启动参数。', secret: false, configured: true,
        value: '--server.port=8080 --shadowflow.notify.enabled=true'},
    {name: 'LOG', label: '日志文件', hint: '日志文件。', secret: false, configured: true, value: 'app.log'}
];

function fixtureFor(url) {
    if (url.indexOf('/api/mode') === 0) return {success: true, data: {dbEnabled: false}};
    if (url.indexOf('/api/auth/me') === 0) return {success: true, data: {loggedIn: true,
        user: {username: 'admin', admin: true}, registrationEnabled: true}};
    if (url.indexOf('/api/home/hero') === 0) return {success: true, data: {configured: false, bars: []}};
    if (url.indexOf('/api/stock-link') === 0) return {success: true, data: {enabled: true, defaultTarget: 'chart', targets: []}};
    if (url.indexOf('/api/admin/integration') === 0) return {success: true, data: {items: [], overridden: 0, total: 0}};
    if (url.indexOf('/api/admin/env') === 0) return {success: true, data: {secrets: SECRETS_FIXTURE, runtimes: RUNTIMES_FIXTURE}};
    return {success: true, data: {}};
}

(async function main() {
    const sysHtml = read(path.join(STATIC, 'pages/system.html'));
    const css = read(path.join(STATIC, 'css/common.css'));

    const dom = new JSDOM('<!DOCTYPE html><html><body><div id="msgBox"></div></body></html>',
        {runScripts: 'dangerously', url: 'http://127.0.0.1:8099/'});
    const win = dom.window;

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

    // 按 onclick 找按钮，不绑死文案（v1.6.2 起按钮文案由「环境变量查看」收短为「环境查看」）
    const btn = [...win.document.querySelectorAll('.cfg-panel-actions button')]
        .find(b => (b.getAttribute('onclick') || '').indexOf('openEnvModal') >= 0);
    ok(!!btn, '系统配置页工具条上有「环境查看」按钮');

    const mask = win.document.getElementById('envMask');
    ok(!!mask, '页面里有 #envMask 弹窗容器');
    ok(mask && !mask.classList.contains('show'), '弹窗默认不展开');
    ok(!calls.some(u => u.indexOf('/api/admin/env') === 0), '没点按钮就不该请求环境变量接口',
        calls.filter(u => u.indexOf('/api/admin/env') === 0).join(',') || '无');

    btn.click();
    await sleep(300);

    ok(calls.some(u => u.indexOf('/api/admin/env') === 0), '点按钮后请求了 /api/admin/env');
    ok(mask.classList.contains('show'), '弹窗已展开');

    const tables = mask.querySelectorAll('table.grid');
    ok(tables.length === 2, `凭据 / 运行参数两张表`, '实测 ' + tables.length);
    const secretRows = tables[0] ? tables[0].querySelectorAll('tbody tr').length : 0;
    const runtimeRows = tables[1] ? tables[1].querySelectorAll('tbody tr').length : 0;
    ok(secretRows === SECRETS_FIXTURE.length, `凭据表渲染出 ${SECRETS_FIXTURE.length} 行`, '实测 ' + secretRows);
    ok(runtimeRows === RUNTIMES_FIXTURE.length, `运行参数表渲染出 ${RUNTIMES_FIXTURE.length} 行`, '实测 ' + runtimeRows);

    // 只读：弹窗内不得出现任何可写控件
    ok(mask.querySelectorAll('input, textarea, select').length === 0, '弹窗内没有任何输入控件（只读）');

    // 明文不得出现：fixture 的 display 是打码值，页面里若搜到明文说明前端自己拼了原文。
    // 探针取自本机 app.env / 环境变量（见第 1 节），本机没配就按 0 条检查。
    const text = mask.textContent;
    const plainProbes = PLAINS.map(pair => pair[1]);
    ok(plainProbes.every(p => text.indexOf(p) < 0), '弹窗文本里没有凭据明文',
        plainProbes.length ? '' : '本机无真实值，按 0 条探针检查');
    ok(text.indexOf('cccc****dddd') >= 0 && text.indexOf('aaaa****bbbb') >= 0, '打码值原样展示');

    // 来源三种口径都要标出来
    const badges = [...mask.querySelectorAll('.ig-src')].map(e => e.textContent.trim());
    ok(badges.includes('系统配置页') && badges.includes('环境变量') && badges.includes('未设置'),
        '来源徽标覆盖 系统配置页 / 环境变量 / 未设置', badges.join(' | '));

    // 关闭：按钮与点遮罩两条路径
    const closeBtn = [...mask.querySelectorAll('.modal-actions button')]
        .find(b => b.textContent.trim() === '关闭');
    ok(!!closeBtn, '弹窗有「关闭」按钮');
    closeBtn.click();
    ok(!mask.classList.contains('show'), '点「关闭」后弹窗收起');

    btn.click();
    await sleep(200);
    ok(mask.classList.contains('show'), '再次打开正常');
    mask.dispatchEvent(new win.MouseEvent('mousedown', {bubbles: true}));
    ok(!mask.classList.contains('show'), '点遮罩后弹窗收起');

    dom.window.close();

    console.log('');
    console.log(failed === 0 ? '全部通过' : failed + ' 项失败');
    process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
    console.error('渲染断言异常:', e);
    process.exit(1);
});
