/* 股票选择器组件功能测试（jsdom，不需要浏览器）
 *
 * 为什么要有它：选择器是"是否真的发请求"的闸门，纯接口层测试碰不到组件逻辑
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
 * 注 1：脚本注入必须用 <script> 标签而不是 window.eval —— eval 的顶层 const 不进全局
 *       词法环境，common.js 的 const get/post 会拿不到（浏览器用 <script src> 没这问题）。
 * 注 2：下拉在打开时被挂到 document.body（sp-drop-fixed，用 fixed 定位绕开容器的
 *       overflow 裁剪），所以断言必须查「当前展开的那个 .sp-drop」，不能查 host 内部 ——
 *       早先按 host 内查找，下拉一打开就查不到，测试全是假红。
 * 注 3：v1.6.0 校准过一次。此前 mock 的是老端点 /api/stocks/search，而组件早已改走
 *       /api/dir/stocks（返回 {success, data:[{code,name}]}），空关键字也不再发请求，
 *       于是整段测试在"聚焦后拉到 3 条"就红了 —— 不是组件坏了，是断言过期。
 */
const fs = require('fs');
const path = require('path');
const {JSDOM} = require('jsdom');

const ROOT = path.resolve(__dirname, '..');
const STATIC = path.join(ROOT, 'shadow-flow-biz/src/main/resources/static');
const commonJs = fs.readFileSync(path.join(STATIC, 'js/common.js'), 'utf8');
const pickerJs = fs.readFileSync(path.join(STATIC, 'js/stock-picker.js'), 'utf8');

const sleep = ms => new Promise(r => setTimeout(r, ms));
let failed = 0;
function ok(cond, name, extra) {
    console.log((cond ? '  PASS  ' : '  FAIL  ') + name + (extra ? '  → ' + extra : ''));
    if (!cond) failed++;
}

/** /api/dir/stocks 的返回（模拟后端：完全相等排第一） */
function fixture(url) {
    const m = url.match(/keyword=([^&]*)/);
    const kw = m ? decodeURIComponent(m[1]) : '';
    if (kw === '999999') return [];
    if (kw === '600584') return [{code: '600584', name: '长电科技'}];
    if (kw === '600000') return [{code: '600000', name: '浦发银行'}];
    const base = [
        {code: '600000', name: '浦发银行'},
        {code: '600004', name: '白云机场'},
        {code: '600006', name: '东风汽车'}
    ];
    if (kw === '600') {
        return [{code: '600584', name: '长电科技'}].concat(base);
    }
    return base;
}

(async function main() {
    const dom = new JSDOM(
        '<!DOCTYPE html><html><head></head><body><div id="msgBox"></div>'
        + '<div id="hostS"></div><div id="hostM"></div><div id="hostX"></div></body></html>',
        {runScripts: 'dangerously', url: 'http://127.0.0.1:8080/'}
    );
    const win = dom.window;
    const doc = win.document;
    const calls = [];
    win.fetch = async (url) => {
        calls.push(url);
        return {status: 200, json: async () => ({success: true, message: 'success', data: fixture(url)})};
    };
    function inject(code) {
        const s = doc.createElement('script');
        s.textContent = code;
        doc.body.appendChild(s);
    }
    inject(commonJs);
    inject(pickerJs);

    /** 当前展开的那个下拉（一次只会有一个是 show） */
    const drop = () => doc.querySelector('.sp-drop.show');
    const opts = () => drop() ? drop().querySelectorAll('.sp-opt') : [];
    /** 名录请求计数：common.js 自己还会拉 /api/mode 等，只数选择器的 */
    const dirCalls = () => calls.filter(u => u.indexOf('/api/dir/stocks?') === 0).length;

    // 严格选择器：不给手输兜底（模拟数据库模式，名录是权威来源）
    const hostS = doc.getElementById('hostS');
    let changeCount = 0;
    const single = win.createStockPicker(hostS, {
        allowManual: () => false,
        onChange: () => changeCount++
    });

    console.log('== 1. 结构与初始态 ==');
    ok(!!hostS.querySelector('.sp-box'), '渲染出 .sp-box');
    ok(!!hostS.querySelector('.sp-input'), '渲染出输入框');
    ok(!doc.querySelector('.sp-drop.show'), '初始没有展开的下拉');
    ok(single.getCodes().length === 0, '初始没有已选项');

    console.log('== 2. 空关键字不打扰后端 ==');
    const before = dirCalls();
    hostS.querySelector('.sp-input').dispatchEvent(new win.Event('focus'));
    await sleep(260);
    ok(dirCalls() === before, '空关键字聚焦不发请求（按规范输入第一个字才查）',
        '新增 ' + (dirCalls() - before) + ' 次');
    ok(!!drop(), '聚焦后下拉展开');
    ok(drop() && drop().textContent.indexOf('代码或名称') >= 0, '下拉里是「代码或名称」引导',
        drop() ? drop().textContent.trim() : '(无下拉)');

    console.log('== 3. 输入 → 去抖 → 候选 ==');
    const input = hostS.querySelector('.sp-input');
    input.value = '600';
    input.dispatchEvent(new win.Event('input'));
    await sleep(60);
    ok(dirCalls() === before, '输入后 60ms 还没发请求（去抖生效）');
    await sleep(240);
    ok(dirCalls() === before + 1, '去抖结束后发了一次请求', String(dirCalls() - before) + ' 次');
    ok(opts().length === 4, '关键字 600 命中 4 条（精确加权首位）', '实测 ' + opts().length);
    ok(opts()[0].querySelector('.code').textContent === '600584', '首条是精确命中项',
        opts()[0].textContent.trim());
    ok(opts()[0].classList.contains('active'), '默认高亮第一条');

    console.log('== 4. 点选 ==');
    const before4 = changeCount;
    opts()[0].dispatchEvent(new win.MouseEvent('click', {bubbles: true}));
    await sleep(30);
    ok(single.getCodes()[0] === '600584', '点选后选中 600584', single.getCodes().join(','));
    ok(input.value === '600584 长电科技', '输入框显示「代码 名称」', input.value);
    ok(single.getFirst().name === '长电科技', 'getFirst 带出名称');
    ok(!doc.querySelector('.sp-drop.show'), '选完自动收起');
    ok(changeCount === before4 + 1, 'onChange 触发 1 次');

    console.log('== 5. 键盘：↓ + Enter ==');
    single.clear();
    input.value = '600';
    input.dispatchEvent(new win.Event('input'));
    await sleep(280);
    input.dispatchEvent(new win.KeyboardEvent('keydown', {key: 'ArrowDown', bubbles: true}));
    ok(opts()[1].classList.contains('active'), '↓ 高亮下移一位');
    input.dispatchEvent(new win.KeyboardEvent('keydown', {key: 'Enter', bubbles: true}));
    await sleep(30);
    ok(single.getCodes()[0] === '600000', 'Enter 选中当前高亮项', single.getCodes().join(','));
    input.dispatchEvent(new win.KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));

    console.log('== 6. 未点选必须拦下（严格模式）==');
    single.clear();
    input.value = '999999';
    input.dispatchEvent(new win.Event('input'));
    await sleep(280);
    ok(single.getCodes().length === 0, '随手填的编号不算选中');
    ok(single.hasUnresolvedInput() === true, 'hasUnresolvedInput 识别未决输入');
    ok(win.requireStock(single, '一只股票') === false, 'requireStock 拦截提交');
    await sleep(60);   // requireStock 内部 focus() 会再搜一次，等它落地
    const tip = drop() ? drop().textContent : '';
    ok(tip.indexOf('没有匹配的股票：999999') >= 0, '下拉给出「没有匹配的股票：999999」', tip.trim());
    ok(tip.indexOf('可直接输入完整代码') === -1, '严格模式下不引导手输（allowManual=false）');

    console.log('== 7. 手输兜底（无数据库模式口径）==');
    const hostX = doc.getElementById('hostX');
    const loose = win.createStockPicker(hostX, {allowManual: () => true});
    const xInput = hostX.querySelector('.sp-input');
    xInput.value = '999999';
    xInput.dispatchEvent(new win.Event('input'));
    await sleep(280);
    ok(xInput.value.indexOf('999999') >= 0, '输入框保留用户敲的代码', xInput.value);
    ok(win.requireStock(loose, '一只股票') === true, '允许手输时 requireStock 放行（补成选中项）');
    ok(loose.getCodes()[0] === '999999', '手输代码被补成已选', loose.getCodes().join(','));
    loose.clear();
    xInput.value = 'abc';
    xInput.dispatchEvent(new win.Event('input'));
    await sleep(280);
    ok(win.requireStock(loose, '一只股票') === false, '非纯数字代码不放行',
        loose.getCodes().join(','));

    console.log('== 8. 多选：chips 与已选集合 ==');
    // 先把前面两个选择器的下拉收掉：它们也挂在 document.body 上，
    // 留着的话 doc.querySelector('.sp-drop.show') 会拿到别人的下拉。
    ['hostS', 'hostX'].forEach(id => {
        doc.getElementById(id).querySelector('.sp-input')
            .dispatchEvent(new win.KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));
    });
    const hostM = doc.getElementById('hostM');
    let mChanges = 0;
    const multi = win.createStockPicker(hostM, {multiple: true, onChange: () => mChanges++});
    const mInput = hostM.querySelector('.sp-input');
    mInput.value = '600';
    mInput.dispatchEvent(new win.Event('input'));
    await sleep(280);
    // 抓住这一个下拉元素本身：pick() 是原地重绘，引用始终有效
    const mDrop = doc.querySelector('.sp-drop.show');
    ok(!!mDrop, '多选下拉已展开');
    const mOpts = () => mDrop.querySelectorAll('.sp-opt');
    mOpts()[0].dispatchEvent(new win.MouseEvent('click', {bubbles: true}));
    await sleep(30);
    // 多选选完不清候选（要能连着点第二个），所以直接点第二条
    mOpts()[1].dispatchEvent(new win.MouseEvent('click', {bubbles: true}));
    await sleep(30);
    ok(hostM.querySelectorAll('.sp-chip').length === 2, '两个已选标签',
        hostM.querySelectorAll('.sp-chip').length + ' 个');
    ok(multi.getCodes().length === 2, 'getCodes 两条', multi.getCodes().join(','));
    ok(mInput.value === '', '多选选完清空输入框', JSON.stringify(mInput.value));

    const chipsBefore = mChanges;
    hostM.querySelector('.sp-chip i').dispatchEvent(new win.MouseEvent('mousedown', {bubbles: true}));
    await sleep(20);
    ok(hostM.querySelectorAll('.sp-chip').length === 1, '点 × 移除一个标签');
    ok(multi.getCodes().length === 1, '已选同步减少');
    ok(mChanges === chipsBefore + 1, '移除触发 onChange');

    mInput.dispatchEvent(new win.KeyboardEvent('keydown', {key: 'Backspace', bubbles: true}));
    await sleep(20);
    ok(multi.getCodes().length === 0, '空输入按 Backspace 删掉最后一个');

    console.log('== 9. setItems 外部同步（不得触发 onChange）==');
    const syncBefore = mChanges;
    multi.setItems([{code: '600000', name: '浦发银行'}, {code: '600004', name: '白云机场'}]);
    await sleep(20);
    ok(hostM.querySelectorAll('.sp-chip').length === 2, '外部 setItems 渲染 2 个标签');
    ok(mChanges === syncBefore, 'setItems 不触发 onChange（避免回环）', '变化 ' + (mChanges - syncBefore));
    ok(multi.getCodes().join(',') === '600000,600004', '已选与 setItems 一致',
        multi.getCodes().join(','));

    console.log('== 10. pickByCode 反查 ==');
    const hit = await single.pickByCode('600584');
    await sleep(20);
    ok(hit === true, '列表中存在的代码反查成功');
    ok(single.getCodes()[0] === '600584', '反查后即选中', single.getCodes().join(','));
    const miss = await single.pickByCode('999999');
    await sleep(20);
    ok(miss === false, '列表中没有的代码返回 false');
    ok(single.getCodes().length === 0 && single.getInputText() === '999999',
        '未命中只填文本、不算选中（严格模式下提交会被拦）', single.getInputText());

    console.log('== 11. 请求参数 ==');
    const dirs = calls.filter(u => u.indexOf('/api/dir/stocks?') === 0);
    ok(dirs.length > 0, '确实打过名录接口', dirs.length + ' 次');
    ok(dirs.every(u => u.indexOf('limit=10') >= 0), '默认 limit=10');
    ok(dirs.some(u => u.indexOf('keyword=600') >= 0), 'keyword 带上了输入内容');
    ok(dirs.some(u => u.indexOf('keyword=600584') >= 0), 'pickByCode 走同一个接口');

    console.log('');
    console.log(failed === 0 ? '全部通过' : failed + ' 项失败');
    dom.window.close();
    process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
    console.error('测试异常:', e);
    process.exit(1);
});
