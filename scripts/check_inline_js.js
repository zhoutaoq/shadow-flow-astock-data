/*
 * 抽取各页面内联 <script>（无 src 属性）做语法检查，避免"页面白屏但后端没报错"。
 *
 * 用法（项目根目录执行）：
 *   node scripts/check_inline_js.js shadow-flow-biz/src/main/resources/static
 *
 * 无第三方依赖，只要 node。
 *
 * 实现说明：用 vm.Script 在<b>本进程内</b>编译而不执行。
 * 早先的写法是写临时文件再 spawn `node --check`，两个问题：
 *   ① 在 Windows 上 spawn 自己那支 node.exe 会偶发 EBUSY（杀软/文件锁），整轮全红；
 *   ② 临时文件按 .mjs 存，等于按 ES module 解析，与页面里真实的 classic script
 *      语义并不一致（顶层 await / import 的判定都不一样）。
 * vm.Script 编译的是 classic script，正好对上页面真实的执行环境。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const base = process.argv[2];
if (!base || !fs.existsSync(base)) {
  console.error('用法: node scripts/check_inline_js.js <静态资源目录>');
  process.exit(2);
}

const files = [];
(function walk(dir) {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name);
    if (fs.statSync(p).isDirectory()) walk(p);
    else if (name.endsWith('.html')) files.push(p);
  }
})(base);

let bad = 0;
for (const f of files) {
  const html = fs.readFileSync(f, 'utf8');
  const re = /<script\b([^>]*)>([\s\S]*?)<\/script>/g;
  let m, i = 0;
  while ((m = re.exec(html)) !== null) {
    if (/\bsrc\s*=/.test(m[1])) continue;
    i++;
    const code = m[2];
    if (!code.trim()) continue;
    try {
      // 只编译不执行：语法错在这里就会抛，且不会真的跑页面脚本
      new vm.Script(code, {filename: `${path.relative(base, f)}#${i}`});
      console.log(`OK   ${path.relative(base, f)} #${i}`);
    } catch (e) {
      bad++;
      console.log(`FAIL ${path.relative(base, f)} #${i}\n${String(e.message).split('\n').slice(0, 6).join('\n')}`);
    }
  }
}
console.log(bad === 0 ? '\n全部内联 JS 语法通过' : `\n${bad} 处语法错误`);
process.exit(bad === 0 ? 0 : 1);
