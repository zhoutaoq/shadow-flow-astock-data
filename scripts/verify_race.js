const fs = require('fs');
const vm = require('vm');

// 模拟浏览器真实时序：common.js 注册 DOMContentLoaded -> 内联脚本先跑(发起 loadMode fetch)
// -> DOMContentLoaded 触发(此时 fetch 可能还没回来) -> renderNav
async function scenario(noDb) {
  const src = fs.readFileSync('shadow-flow-biz/src/main/resources/static/js/common.js', 'utf8');
  const html = fs.readFileSync('shadow-flow-biz/src/main/resources/static/index.html', 'utf8');
  const inline = html.match(/<script>([\s\S]*?)<\/script>/)[1];

  const domListeners = {};
  const store = {};
  function el(id) {
    return {
      id, style: {}, _html: '', _text: '',
      set innerHTML(v) { this._html = v; store[id] = v; },
      get innerHTML() { return this._html; },
      set textContent(v) { this._text = v; },
      get textContent() { return this._text; },
      // applyTheme() 拿到 #themeToggle 后会写 title / aria-label，桩里得有这两个入口
      setAttribute() {},
      getAttribute: () => null,
      addEventListener() {},
      // renderNav() 挂完菜单会遍历 a[data-login] 绑事件；桩里给空集合即可
      querySelectorAll: () => [],
      querySelector: () => null
    };
  }
  const sb = {};
  sb.console = console;
  sb.setTimeout = setTimeout;
  sb.clearTimeout = clearTimeout;
  sb.CustomEvent = function () {};
  sb.location = { pathname: '/' };
  // 模拟网络延迟，让 fetch 一定晚于 DOMContentLoaded 返回
  sb.fetch = async (url) => {
    await new Promise(r => setTimeout(r, 30));
    if (String(url).includes('/api/mode')) {
      return { json: async () => ({ success: true, data: { dbEnabled: !noDb, maxSpanDays: 1200, tokenFromRequest: true } }) };
    }
    return { json: async () => ({ success: true, data: { stockCount: 1, klineCount: 2, coveredStockCount: 3 } }) };
  };
  sb.document = {
    cookie: 'shadowflow_token=test', // 跳过令牌弹窗（非本次验证对象）
    // applyTheme() 要往 <html> 上写 data-theme；原来的桩没有 documentElement，
    // common.js 一跑到它就崩在桩上，真正的菜单竞态结果反而看不到。
    documentElement: { setAttribute() {}, getAttribute: () => null },
    head: { appendChild: () => {} },
    getElementById: (id) => el(id),
    querySelector: (sel) => (sel === '.quick' ? el('quick') : el(sel)),
    addEventListener: (name, fn) => { (domListeners[name] = domListeners[name] || []).push(fn); },
    dispatchEvent: () => {},
    body: { appendChild: () => {} }
  };
  // currentTheme() 会读 localStorage；给个空实现，等价于「没手动选过主题」
  sb.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
  // common.js 的 DOMContentLoaded 分支里会读 window.MutationObserver：
  // 桩里不给就是 ReferenceError，等于把菜单渲染整段打断了。
  sb.window = {};
  // vm 的新上下文里只有 ECMAScript 内置对象，浏览器那批全局得自己塞进去
  // （少了就会一路 ReferenceError，把真正要验的菜单渲染盖掉）
  sb.URLSearchParams = URLSearchParams;
  sb.URL = URL;
  sb.navigator = { userAgent: 'node', clipboard: { writeText: async () => {} } };
  sb.matchMedia = () => ({ matches: false, addEventListener() {}, addListener() {} });
  sb.getComputedStyle = () => ({ getPropertyValue: () => '' });
  vm.createContext(sb);
  vm.runInContext(src, sb);          // 注册 DOMContentLoaded
  vm.runInContext(inline, sb);       // 内联脚本：先发起 loadMode
  // 触发 DOMContentLoaded 并等它跑完
  for (const fn of domListeners['DOMContentLoaded'] || []) {
    await fn();
  }
  await new Promise(r => setTimeout(r, 50)); // 让内联 IIFE 的后续也落地

  const nav = store['nav'] || '';
  const quick = store['quick'] || '';
  const navTexts = [...nav.matchAll(/class="nav-item[^"]*"[^>]*>([^<]+)</g)].map(m => m[1]);
  const quickTexts = [...quick.matchAll(/<b>([^<]+)<\/b>/g)].map(x => x[1]);
  return { navTexts, quickTexts, group: /nav-group/.test(nav) };
}

let failed = 0;
function ok(cond, name, extra) {
  console.log((cond ? '  PASS  ' : '  FAIL  ') + name + (extra ? '  \u2192 ' + extra : ''));
  if (!cond) failed++;
}

(async () => {
  // 匿名身份下应看到的菜单（admin 专属的「系统配置」「用户管理」不在内）
  const FLAT = ['概览', '股票列表', 'K线查看', '实时资讯', '策略回测', '盯盘', '我的账户', '用户反馈'];

  console.log('== 场景A：无数据库模式（真实事件时序，含竞态窗口）==');
  const a = await scenario(true);
  console.log('  导航栏: ' + JSON.stringify(a.navTexts) + ' | 有分组=' + (a.group ? '是' : '否'));
  ok(!a.group, 'v1.3 起不再有分组菜单（两种模式共用一条扁平菜单）');
  ok(a.navTexts.length === FLAT.length && FLAT.every(t => a.navTexts.includes(t)),
    '匿名身份下菜单为 8 项且内容完整', a.navTexts.join('/'));
  ok(!a.navTexts.includes('系统配置') && !a.navTexts.includes('用户管理'),
    'admin 专属两项对匿名不可见');

  console.log('== 场景B：数据库模式（菜单应与 A 完全一致）==');
  const b = await scenario(false);
  console.log('  导航栏: ' + JSON.stringify(b.navTexts) + ' | 有分组=' + (b.group ? '是' : '否'));
  ok(!b.group, '数据库模式同样是扁平菜单');
  ok(b.navTexts.join(',') === a.navTexts.join(','), '两种模式菜单一致', b.navTexts.join('/'));

  console.log('');
  console.log(failed === 0 ? '全部通过' : failed + ' 项失败');
  process.exit(failed === 0 ? 0 : 1);
})();
