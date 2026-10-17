/*
 * v1.6.2 自测（jsdom，不需要浏览器、不需要起服务）
 *
 * 覆盖 releasev1.6.2 的三条需求：
 *   1. 菜单名称、顺序可配 —— 收在系统配置页，「按钮 + 弹窗」提供，默认 = 当前数据；
 *   2. 系统 logo —— 有默认图，系统配置页可上传更新；
 *   3. 「用户反馈」→「问题反馈」文案统一，反馈页主输入框放大。
 *
 * 准备（一次性，jsdom 装在隔离工作区）：
 *   cd %USERPROFILE%\.workbuddy\binaries\node\workspace && npm install jsdom
 * 运行（项目根目录）：
 *   NODE_PATH=%USERPROFILE%\.workbuddy\binaries\node\workspace\node_modules \
 *     node scripts/check_v162.js
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

/* =================== 1. 静态：「用户反馈」已全站消失 =================== */
console.log('== 1. 「用户反馈」文案统一 ==');

// 这些是会进包、会渲染到界面的文件；docs/ 是历史设计记录，不改（叙述过去的口径）。
const UI_FILES = [
    'shadow-flow-biz/src/main/resources/static/index.html',
    'shadow-flow-biz/src/main/resources/static/js/common.js',
    'shadow-flow-biz/src/main/resources/static/pages/feedback.html',
    'shadow-flow-biz/src/main/resources/static/pages/system.html',
    'shadow-flow-biz/src/main/resources/static/pages/users.html',
    'shadow-flow-biz/src/main/resources/static/pages/account.html',
    'shadow-flow-biz/src/main/resources/static/pages/watch.html',
    'shadow-flow-biz/src/main/resources/static/pages/chart.html',
    'shadow-flow-biz/src/main/resources/static/pages/backtest.html',
    'shadow-flow-biz/src/main/resources/static/pages/stock-list.html',
    'shadow-flow-biz/src/main/resources/static/pages/news.html'
];
const leftOver = [];
UI_FILES.forEach(rel => {
    const p = path.join(ROOT, rel);
    if (!fs.existsSync(p)) return;
    const src = read(p);
    // 页面里出现「用户反馈」= 改漏了（注释里也不该留旧口径，免得下次照着抄）
    src.split(/\r?\n/).forEach((line, i) => {
        if (line.includes('用户反馈')) {
            leftOver.push(rel.replace(/\\/g, '/') + ':' + (i + 1));
        }
    });
});
ok(leftOver.length === 0, 'UI 文件里不再出现「用户反馈」', leftOver.join(' '));

// 钉钉消息首行：机器人「自定义关键词」模式靠它命中，改了要一起改
const fbService = read(path.join(ROOT, 'shadow-flow-biz/src/main/java/io/github/zhoutaoq/shadowflow/biz/feedback/FeedbackService.java'));
ok(fbService.includes('"问题反馈\\n"'), '钉钉消息首行写成「问题反馈」');
ok(!fbService.includes('"用户反馈\\n"'), '钉钉消息首行不再拼「用户反馈」');

// 后端菜单唯一真相源里也已经是新名字
const menuSvc = read(path.join(ROOT, 'shadow-flow-biz/src/main/java/io/github/zhoutaoq/shadowflow/biz/service/MenuConfigService.java'));
ok(menuSvc.includes('new BuiltIn("feedback", "问题反馈"'), 'MenuConfigService 内置菜单名称已是「问题反馈」');

/*
 * 菜单是「后端 BUILT_IN + 前端 NAV_ITEMS」两处维护的同一份真相，两边必须一一对应。
 * 数量不写死：从源码里数出来。只改一边（比如新加了页面却没登记）会在这里直接失败 ——
 * v1.6.6 加「钉钉推送」时就漏了后端，导致配置页久久看不到那一项。
 */
function parseBuiltIns(javaSrc) {
    const out = [];
    const re = /new BuiltIn\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)"\)/g;
    let m;
    while ((m = re.exec(javaSrc))) out.push({key: m[1], text: m[2], href: m[3]});
    return out;
}
function parseNavItems(jsSrc) {
    const out = [];
    const re = /\{key:\s*'([^']+)',\s*text:\s*'([^']+)',\s*href:\s*'([^']+)'/g;
    let m;
    while ((m = re.exec(jsSrc))) out.push({key: m[1], text: m[2], href: m[3]});
    return out;
}
const BACKEND_MENUS = parseBuiltIns(menuSvc);
const FRONT_MENUS = parseNavItems(read(path.join(STATIC, 'js/common.js')));
const MENU_COUNT = BACKEND_MENUS.length;
ok(MENU_COUNT > 0, '从 MenuConfigService 解析出内置菜单', String(MENU_COUNT));
ok(BACKEND_MENUS.map(x => x.key).join(',') === FRONT_MENUS.map(x => x.key).join(','),
    '前后端菜单 key 与顺序一致',
    '后端 ' + BACKEND_MENUS.map(x => x.key).join('/') + ' ↔ 前端 ' + FRONT_MENUS.map(x => x.key).join('/'));
ok(FRONT_MENUS.every((it, i) => !BACKEND_MENUS[i] || BACKEND_MENUS[i].href === it.href),
    '前后端菜单地址一致');

/* =================== 2. 静态：反馈输入框放大 =================== */
console.log('== 2. 问题反馈主输入框 ==');

const css = read(path.join(STATIC, 'css/common.css'));
const taBlock = (css.match(/\.fb-field textarea\s*\{[\s\S]*?\}/) || [''])[0];
const mh = /min-height:\s*(\d+)px/.exec(taBlock);
ok(!!mh && Number(mh[1]) >= 300, '输入框 min-height ≥ 300px（原 170px）', mh ? mh[1] + 'px' : '未取到');
const panelBlock = (css.match(/\.fb-panel\s*\{[^}]*\}/) || [''])[0];
const mw = /max-width:\s*(\d+)px/.exec(panelBlock);
ok(!!mw && Number(mw[1]) >= 1000, '反馈面板 max-width ≥ 1000px（原 880px）', mw ? mw[1] + 'px' : '未取到');

/* =================== 3. 静态：新接口的可见性 =================== */
console.log('== 3. 新接口的登录口径 ==');

const authWeb = read(path.join(ROOT, 'shadow-flow-biz/src/main/java/io/github/zhoutaoq/shadowflow/biz/auth/AuthWebConfig.java'));
ok(authWeb.includes('"/api/admin/**"'), '/api/admin/** 在 PROTECTED 里（上传 / 保存要登录）');
// 读接口不能挂在登录后面：导航每个页面都要渲染、<img> 请求不带自定义头
ok(!/PROTECTED[\s\S]{0,400}"\/api\/menu/.test(authWeb), '/api/menu 不在 PROTECTED 里（匿名可读）');
ok(!/PROTECTED[\s\S]{0,400}"\/api\/logo/.test(authWeb), '/api/logo 不在 PROTECTED 里（匿名可读）');

const controllers = [
    ['web/MenuController.java', '/api/menu'],
    ['web/AdminMenuController.java', '/api/admin/menu'],
    ['web/LogoController.java', '/api/logo'],
    ['web/AdminLogoController.java', '/api/admin/logo']
];
controllers.forEach(([rel, mapping]) => {
    const p = path.join(ROOT, 'shadow-flow-biz/src/main/java/io/github/zhoutaoq/shadowflow/biz/' + rel);
    ok(fs.existsSync(p) && read(p).includes('@RequestMapping("' + mapping + '")'),
        rel + ' 映射到 ' + mapping);
});

// multipart 上限必须大于自校验的 2MB，否则 1–2MB 的图会在容器层先被拒、报英文异常
const yml = read(path.join(ROOT, 'shadow-flow-biz/src/main/resources/application.yml'));
const mf = /max-file-size:\s*(\d+)MB/.exec(yml);
ok(!!mf && Number(mf[1]) >= 2, 'multipart max-file-size ≥ 2MB（与 LogoService 自校验对齐）',
    mf ? mf[1] + 'MB' : '未取到');

/* =================== 4. jsdom：菜单配置弹窗与导航按配置渲染 =================== */
console.log('== 4. 菜单配置（jsdom） ==');

let JSDOM;
try {
    JSDOM = require('jsdom').JSDOM;
} catch (e) {
    console.log('  SKIP  未找到 jsdom，跳过渲染断言（装法见本文件头注释）');
    console.log('\n' + (failed === 0 ? '静态检查全部通过（渲染断言已跳过）' : failed + ' 项失败'));
    process.exit(failed === 0 ? 0 : 1);
}

// 服务端下发的菜单配置：把「问题反馈」提到第 2 位、改名「反馈建议」，
// 并且只给一部分 key —— 用来验证「漏项按内置顺序补队尾」
const MENU_FIXTURE = {
    customized: true,
    items: [
        {key: 'home', text: '首页', href: '/'},
        {key: 'feedback', text: '反馈建议', href: '/pages/feedback.html'},
        {key: 'chart', text: 'K线查看', href: '/pages/chart.html'}
    ]
};

function fixtureFor(url) {
    if (url.indexOf('/api/mode') === 0) return {success: true, data: {dbEnabled: false}};
    if (url.indexOf('/api/menu') === 0) return {success: true, data: MENU_FIXTURE};
    if (url.indexOf('/api/logo') === 0) return {success: true, data: {url: '/api/logo/view?v=123', customized: true}};
    if (url.indexOf('/api/auth/me') === 0) return {success: true, data: {loggedIn: true,
        user: {username: 'admin', admin: true}, registrationEnabled: true}};
    if (url.indexOf('/api/stock-link') === 0) return {success: true, data: {enabled: true, defaultTarget: 'chart', targets: []}};
    if (url.indexOf('/api/admin/integration') === 0) return {success: true, data: {items: [], overridden: 0, total: 0}};
    if (url.indexOf('/api/home/hero') === 0) return {success: true, data: {configured: false, bars: []}};
    if (url.indexOf('/api/version') === 0) return {success: true, data: {version: '1.6.2', buildTime: '2026-10-11 10:00'}};
    if (url.indexOf('/api/account/summary') === 0) return {success: false};
    return {success: true, data: {}};
}

function makeDom(html) {
    const dom = new JSDOM('<!DOCTYPE html><html><body><div id="msgBox"></div></body></html>',
        {runScripts: 'dangerously', url: 'http://127.0.0.1:8099/pages/system.html'});
    const win = dom.window;
    const calls = [];
    win.fetch = async (url) => {
        calls.push(String(url));
        return {status: 200, json: async () => fixtureFor(String(url))};
    };
    function inject(code) {
        const s = win.document.createElement('script');
        s.textContent = code;
        win.document.body.appendChild(s);
    }
    inject(read(path.join(STATIC, 'js/common.js')));
    ['date-picker.js', 'stock-picker.js'].forEach(f => {
        const p = path.join(STATIC, 'js/' + f);
        if (fs.existsSync(p)) inject(read(p));
    });
    const bodyInner = html.replace(/[\s\S]*?<body>/, '').replace(/<\/body>[\s\S]*$/, '')
        .replace(/<script\b[^>]*\bsrc=[^>]*>\s*<\/script>/g, '');
    const holder = win.document.createElement('div');
    holder.innerHTML = bodyInner;
    win.document.body.appendChild(holder);
    const inline = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)]
        .filter(m => !/\bsrc\s*=/.test(m[1])).map(m => m[2]).join('\n');
    inject(inline);
    return {dom, win, calls};
}

(async function main() {
    const sysHtml = read(path.join(STATIC, 'pages/system.html'));

    /* --- 4a. 导航按配置渲染 --- */
    const a = makeDom(sysHtml);
    await sleep(400);

    const nav = a.win.document.getElementById('nav');
    const navTexts = [...nav.querySelectorAll('a.nav-item')].map(x => x.textContent.trim());
    ok(navTexts[0] === '首页', '导航第 1 项用了配置里的名称', navTexts[0]);
    ok(navTexts[1] === '反馈建议', '导航第 2 项按配置提前且改了名', navTexts[1]);
    ok(navTexts[2] === 'K线查看', '导航第 3 项按配置顺序', navTexts[2]);
    // 配置里没有的 key 必须补回（按内置顺序追加到队尾），不能丢
    ok(navTexts.includes('股票列表') && navTexts.includes('实时资讯') && navTexts.includes('用户管理'),
        '配置漏掉的菜单按内置顺序补回队尾', navTexts.join(' · '));
    ok(navTexts.length === MENU_COUNT, `补齐后仍是 ${MENU_COUNT} 项`, String(navTexts.length));
    // 导航里出现的名称必须是配置名（旧名不能残留）
    ok(!navTexts.includes('问题反馈'), '改名后导航里不再出现内置旧名「问题反馈」');

    /* --- 4b. 菜单配置按钮 + 弹窗 --- */
    const menuBtn = [...a.win.document.querySelectorAll('button')]
        .find(b => b.textContent.trim() === '菜单配置');
    ok(!!menuBtn, '系统配置页有「菜单配置」按钮');

    // 弹窗是懒建的：点按钮才创建 DOM，避免每个页面的 body 尾部都挂一个空壳
    ok(!a.win.document.getElementById('menuMask'), '未点按钮时不建弹窗 DOM');

    menuBtn.click();
    await sleep(300);
    const mask = a.win.document.getElementById('menuMask');
    ok(!!mask, '点按钮后菜单配置弹窗（#menuMask）已建出来');
    ok(mask.classList.contains('show'), '点按钮后弹窗展开');

    const rows0 = mask.querySelectorAll('.menu-row');
    ok(rows0.length === MENU_COUNT, `弹窗里列出了全部 ${MENU_COUNT} 个菜单项`, String(rows0.length));
    const nameInputs = [...mask.querySelectorAll('.menu-text')].map(i => i.value);
    ok(nameInputs[0] === '首页' && nameInputs[1] === '反馈建议',
        '弹窗里回显的是「当前数据」', nameInputs.slice(0, 3).join(' | '));
    // 配置里没下发的项（服务端裁剪时）也必须补进草稿，否则超管配不到它们
    ok(nameInputs.includes('股票列表') && nameInputs.includes('用户管理'),
        '配置未下发的项也补进弹窗（内置补齐）');

    // 上移：把第 2 行（反馈建议）移到第 1 位
    rows0[1].querySelector('button[data-act="up"]').click();
    await sleep(60);
    const after = [...mask.querySelectorAll('.menu-text')].map(i => i.value);
    ok(after[0] === '反馈建议' && after[1] === '首页', '点「↑」后两项就地换位', after.slice(0, 2).join(' | '));

    // 下移回来，顺带验证边界按钮禁用
    mask.querySelectorAll('.menu-row')[0].querySelector('button[data-act="down"]').click();
    await sleep(60);
    const rows = mask.querySelectorAll('.menu-row');
    ok(rows[0].querySelector('button[data-act="up"]').disabled, '第 1 项的上移按钮禁用');
    ok(rows[rows.length - 1].querySelector('button[data-act="down"]').disabled, '最后一项的下移按钮禁用');

    // 名称不落字面量：弹窗里的输入框必须是可编辑的 text
    ok(mask.querySelectorAll('input.menu-text').length === MENU_COUNT, '每行一个可编辑的名称输入框');
    // 地址不给配：只以只读文本展示
    ok(mask.querySelectorAll('.menu-row input[type="text"]').length === 0
        || [...mask.querySelectorAll('.menu-row input')].every(i => i.classList.contains('menu-text')),
        '弹窗里只有名称是可编辑项，地址不给配');

    // 保存：发一次 POST /api/admin/menu，随后本页导航就地重绘
    const saveBtn = mask.querySelector('#menuSave');
    ok(!!saveBtn, '弹窗有「保存」按钮');
    let posted = null;
    a.win.fetch = async (url, opt) => {
        if (String(url).indexOf('/api/admin/menu') === 0) {
            posted = {url: String(url), body: opt && opt.body};
            return {status: 200, json: async () => ({success: true, message: '菜单已保存并生效', data: MENU_FIXTURE})};
        }
        return {status: 200, json: async () => fixtureFor(String(url))};
    };
    saveBtn.click();
    await sleep(200);
    ok(!!posted && posted.url.indexOf('/api/admin/menu') === 0, '点「保存」提交到 /api/admin/menu');
    ok(!!posted && /"key"/.test(String(posted.body)) && /"text"/.test(String(posted.body)),
        '提交体里只有 key 与 text（href 不参与保存）');
    ok(!posted || String(posted.body).indexOf('href') < 0, '提交体里不含 href');
    ok(!mask.classList.contains('show'), '保存成功后弹窗自动收起');

    /* --- 4c. logo 预览与上传入口 --- */
    const logoPrev = a.win.document.getElementById('logoPrev');
    ok(!!logoPrev, '系统配置页有 logo 预览图');
    ok(logoPrev && logoPrev.src.indexOf('/api/logo/view') >= 0, '预览图用的是服务端下发的自定义地址',
        logoPrev ? logoPrev.src : '');
    const logoState = a.win.document.getElementById('logoState');
    ok(logoState && logoState.textContent.includes('自定义'), '状态行标出「已使用自定义 logo」',
        logoState ? logoState.textContent : '');
    const uploadBtn = [...a.win.document.querySelectorAll('button')]
        .find(b => b.textContent.trim() === '上传新 logo');
    ok(!!uploadBtn, '有「上传新 logo」按钮');
    uploadBtn.click();
    await sleep(60);
    const fileInput = a.win.document.getElementById('logoFile');
    ok(!!fileInput && fileInput.type === 'file', '点按钮会拉起文件选择器（隐藏 input[type=file]）');
    ok(!!fileInput && /image\//.test(fileInput.accept), '文件选择器限定了图片类型',
        fileInput ? fileInput.accept : '');

    /* --- 4d. 顶栏品牌挂上了 logo --- */
    const brand = a.win.document.querySelector('.topbar .brand');
    ok(!!brand && !!brand.querySelector('img.brand-logo'), '顶栏品牌位渲染出 logo 图');
    ok(!!brand && !!brand.querySelector('.brand-text'), '顶栏品牌位保留名称文本');
    ok(!!brand && brand.querySelector('img.brand-logo').src.indexOf('/api/logo/view') >= 0,
        '顶栏 logo 用的是配置后的地址');

    a.dom.window.close();

    console.log('');
    console.log(failed === 0 ? '全部通过' : failed + ' 项失败');
    process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
    console.error('渲染断言异常:', e);
    process.exit(1);
});
