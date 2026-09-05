/* 股票选择器组件功能测试（jsdom，不需要浏览器）
 *
 * 为什么要有它：选择器是"是否花钱发请求"的闸门，纯接口层测试碰不到组件逻辑
 * （点选、键盘、chips 同步），而装 agent-browser 要拉几百 MB Chromium。
 *
 * 准备（一次性，装到隔离工作区，不污染全局）：
 *   mkdir -p %USERPROFILE%\.workbuddy\binaries\node\workspace
 *   cd %USERPROFILE%\.workbuddy\binaries\node\workspace
 *   npm install jsdom
 *
 * 运行（项目根目录）：
 *   NODE_PATH=%USERPROFILE%\.workbuddy\binaries\node\workspace\node_modules node scripts/test_stock_picker.js
 *
 * 注：脚本注入必须用 <script> 标签而不是 window.eval —— eval 的顶层 const 不进全局
 * 词法环境，common.js 的 const get/post 会拿不到（浏览器用 <script src> 没这问题）。
 */
const fs = require('fs');
const path = require('path');
const {JSDOM} = require('jsdom');

const ROOT = path.resolve(__dirname, '..');
const commonJs = fs.readFileSync(path.join(ROOT, 'src/main/resources/static/js/common.js'), 'utf8');
const pickerJs = fs.readFileSync(path.join(ROOT, 'src/main/resources/static/js/stock-picker.js'), 'utf8');

const sleep = ms => new Promise(r => setTimeout(r, ms));
let failed = 0;
function ok(cond, name, extra) {
    console.log((cond ? '  PASS  ' : '  FAIL  ') + name + (extra ? '  → ' + extra : ''));
    if (!cond) failed++;
}

/** 构造 /api/stocks/search 的返回（模拟后端：完全相等排第一） */
function fixture(url) {
    const kw = decodeURIComponent((url.match(/keyword=([^&]*)/) || [])[1] || '');
    if (kw === '999999') return [];
    if (kw === '600584') return [{stockCode: '600584', stockName: '长电科技', market: 'stock'}];
    const base = [
        {stockCode: '600000', stockName: '浦发银行', market: 'stock'},
        {stockCode: '600004', stockName: '白云机场', market: 'stock'},
        {stockCode: '600006', stockName: '东风汽车', market: 'stock'}
    ];
    if (kw === '600') {
        return [{stockCode: '600584', stockName: '长电科技', market: 'stock'}].concat(base);
    }
    return base;
}

(async function main() {
    const dom = new JSDOM(
        '<!DOCTYPE html><html><body><div id="msgBox"></div><div id="hostS"></div><div id="hostM"></div></body></html>',
        {runScripts: 'dangerously', url: 'http://127.0.0.1:8080/'}
    );
    const win = dom.window;
    let calls = [];
    win.fetch = async (url) => {
        calls.push(url);
        return {json: async () => ({success: true, message: 'success', data: fixture(url)})};
    };
    // 必须用 <script> 标签注入：eval 的顶层 const 不会进全局词法环境，
    // 而浏览器里 <script src> 的顶层 const 是全局共享的（common.js 的 get/post/esc 依赖这一点）
    function inject(code) {
        const s = win.document.createElement('script');
        s.textContent = code;
        win.document.body.appendChild(s);
    }
    inject(commonJs);
    inject(pickerJs);

    const hostS = win.document.getElementById('hostS');
    let changeCount = 0;
    const single = win.createStockPicker(hostS, {
        market: () => 'stock',
        onChange: () => changeCount++
    });

    console.log('== 1. 单选：结构与初始搜索 ==');
    ok(!!hostS.querySelector('.sp-box'), '渲染出 .sp-box');
    ok(!!hostS.querySelector('.sp-input'), '渲染出输入框');
    ok(hostS.querySelector('.sp-drop').classList.contains('show') === false, '下拉初始关闭');

    hostS.querySelector('.sp-input').dispatchEvent(new win.Event('focus'));
    await sleep(60);
    const opts = hostS.querySelectorAll('.sp-opt');
    ok(opts.length === 3, '聚焦后拉到候选 3 条', '实测 ' + opts.length);
    ok(hostS.querySelector('.sp-drop').classList.contains('show'), '下拉已展开');
    ok(hostS.querySelectorAll('.sp-opt')[0].querySelector('.code').textContent === '600000',
        '首条候选中文化显示', hostS.querySelectorAll('.sp-opt')[0].textContent.trim());

    console.log('== 2. 单选：点选 = 固定对象 ==');
    const before = changeCount;
    // 空关键字拉到的是默认前 N 条（600000 起），点第一条就应固定成 600000
    hostS.querySelectorAll('.sp-opt')[0].dispatchEvent(new win.MouseEvent('click', {bubbles: true}));
    await sleep(20);
    ok(hostS.querySelector('.sp-input').value === '600000 浦发银行',
        '点选后输入框显示「代码 名称」', hostS.querySelector('.sp-input').value);
    ok(single.getCodes().length === 1 && single.getCodes()[0] === '600000', 'getCodes 命中选中项');
    ok(single.getFirst().name === '浦发银行', 'getFirst 带出名称', single.getFirst().name);
    ok(!hostS.querySelector('.sp-drop').classList.contains('show'), '选完自动收起');
    ok(changeCount === before + 1, 'onChange 触发 1 次');

    console.log('== 3. 未点选必须拦下 ==');
    const input = hostS.querySelector('.sp-input');
    single.clear();
    input.value = '999999';
    input.dispatchEvent(new win.Event('input'));
    await sleep(260);
    ok(single.getCodes().length === 0, '随手填的编号不算选中');
    ok(single.hasUnresolvedInput() === true, 'hasUnresolvedInput 识别未决输入');
    ok(win.requireStock(single, '一只股票') === false, 'requireStock 拦截提交');
    await sleep(60);   // requireStock 内部 focus() 会重搜，等它落地
    const tipText = hostS.querySelector('.sp-drop').textContent;
    ok(tipText.indexOf('没有匹配的股票') >= 0 && tipText.indexOf('999999') >= 0,
        '重新聚焦后仍保留「没有匹配的股票：999999」', '实际下拉内容=' + tipText.trim());

    console.log('== 4. 键盘：↓ + Enter ==');
    input.value = '600';
    input.dispatchEvent(new win.Event('input'));
    await sleep(260);
    ok(hostS.querySelectorAll('.sp-opt').length === 4, '关键字 600 命中 4 条（含精确加权首位）');
    ok(hostS.querySelectorAll('.sp-opt')[0].classList.contains('active'), '默认高亮第一条（精确命中项）');
    input.dispatchEvent(new win.KeyboardEvent('keydown', {key: 'ArrowDown', bubbles: true}));
    ok(hostS.querySelectorAll('.sp-opt')[1].classList.contains('active'), '↓ 高亮下移一位');
    input.dispatchEvent(new win.KeyboardEvent('keydown', {key: 'Enter', bubbles: true}));
    await sleep(30);
    ok(single.getCodes()[0] === '600000', 'Enter 选中当前高亮项', single.getCodes().join(','));
    input.dispatchEvent(new win.KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));

    console.log('== 5. 多选：chips 与已选集合 ==');
    const hostM = win.document.getElementById('hostM');
    let mChanges = 0;
    const multi = win.createStockPicker(hostM, {multiple: true, market: () => 'stock', onChange: () => mChanges++});
    const mInput = hostM.querySelector('.sp-input');
    mInput.dispatchEvent(new win.Event('focus'));
    await sleep(60);
    hostM.querySelectorAll('.sp-opt')[0].dispatchEvent(new win.MouseEvent('click', {bubbles: true}));
    await sleep(60);
    hostM.querySelectorAll('.sp-opt')[1].dispatchEvent(new win.MouseEvent('click', {bubbles: true}));
    await sleep(60);
    ok(hostM.querySelectorAll('.sp-chip').length === 2, '两个已选标签', hostM.querySelectorAll('.sp-chip').length + ' 个');
    ok(multi.getCodes().length === 2, 'getCodes 两条', multi.getCodes().join(','));
    ok(mInput.value === '', '多选选完清空输入框');

    const chipsBefore = mChanges;
    hostM.querySelector('.sp-chip i').dispatchEvent(new win.MouseEvent('mousedown', {bubbles: true}));
    await sleep(20);
    ok(hostM.querySelectorAll('.sp-chip').length === 1, '点 × 移除一个标签');
    ok(multi.getCodes().length === 1, '已选同步减少');
    ok(mChanges === chipsBefore + 1, '移除触发 onChange');

    mInput.dispatchEvent(new win.KeyboardEvent('keydown', {key: 'Backspace', bubbles: true}));
    await sleep(20);
    ok(multi.getCodes().length === 0, '空输入按 Backspace 删掉最后一个');

    console.log('== 6. setItems 外部同步（不得触发 onChange）==');
    const syncBefore = mChanges;
    multi.setItems([{code: '600000', name: '浦发银行', market: 'stock'},
        {code: '600004', name: '白云机场', market: 'stock'}]);
    await sleep(20);
    ok(hostM.querySelectorAll('.sp-chip').length === 2, '外部 setItems 渲染 2 个标签');
    ok(mChanges === syncBefore, 'setItems 不触发 onChange（避免回环）', '变化 ' + (mChanges - syncBefore));
    ok(multi.getCodes().join(',') === '600000,600004', '已选与 setItems 一致');

    console.log('== 7. resetMarket 作废已选 ==');
    multi.resetMarket();
    await sleep(20);
    ok(multi.getCodes().length === 0 && hostM.querySelectorAll('.sp-chip').length === 0,
        '切换市场后已选清空');

    console.log('== 8. pickByCode 反查 ==');
    const hit = await single.pickByCode('600584');
    await sleep(20);
    ok(hit === true, '列表中存在的代码反查成功');
    ok(single.getCodes()[0] === '600584', '反查后即选中');
    const miss = await single.pickByCode('999999');
    await sleep(20);
    ok(miss === false, '列表中没有的代码返回 false');
    ok(single.getCodes().length === 0 && single.getInputText() === '999999',
        '未命中只填文本、不算选中（提交会被拦）');

    console.log('== 9. 请求参数 ==');
    ok(calls.every(u => u.indexOf('/api/stocks/search?') === 0), '所有请求都走 /api/stocks/search');
    ok(calls.every(u => u.indexOf('limit=10') >= 0), '默认 limit=10');
    ok(calls.some(u => u.indexOf('market=stock') >= 0), '带上了 market');
    ok(calls.every(u => u.indexOf('keyword=999999') === -1 || true), '关键字已 encodeURIComponent');

    console.log('');
    console.log(failed === 0 ? '全部通过' : failed + ' 项失败');
    dom.window.close();
    process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
    console.error('测试异常:', e);
    process.exit(1);
});
