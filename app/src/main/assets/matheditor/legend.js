/*
 * 图例渲染。
 *
 * 对应参考实现的 legend.min.js —— 那是一个 React 组件，渲染出来的 DOM 和这里
 * 一模一样，连量尺寸的算法都照抄（componentDidUpdate 里那段）：
 *
 *   div[text-align:right]                     高度/宽度都是 100%
 *     └ div.rowContainer
 *         ├ div.colorContainer  (float:left, width:30px)  ← 每条一个「N:」，右对齐
 *         └ div.mathContainer   (float:left)              ← 每条一个 MathQuill 静态公式
 *
 * Android 传进来的字符串形如 `y=2x+3&#EFB557$$y=x+1&#62ACFF$$`：
 * 「$$」分隔条目，「&#RRGGBB」是该条的颜色；**没有颜色**的条目按原版约定画成灰色
 * （#AAA）——那是「这条曲线被点掉、暂时不画」的状态。
 *
 * 渲染完把尺寸（CSS px）报回 Android，由 Android 乘密度去摆这个 WebView。
 */
(function (window, document) {
  'use strict';

  var MQ = window.MathQuill.getInterface(2);
  var GREY = '#AAA';

  function div(className) {
    var node = document.createElement('div');
    if (className) node.className = className;
    return node;
  }

  function render(formula) {
    var root = document.getElementById('root');
    while (root.firstChild) root.removeChild(root.firstChild);

    var wrapper = div(null);
    wrapper.setAttribute('contenteditable', 'false');
    wrapper.style.height = '100%';
    wrapper.style.width = '100%';
    wrapper.style.textAlign = 'right';

    var row = div('rowContainer');
    var colors = div('colorContainer');
    var maths = div('mathContainer');
    row.appendChild(colors);
    row.appendChild(maths);
    wrapper.appendChild(row);
    root.appendChild(wrapper);

    var cells = [];
    var parts = String(formula || '').split('$$');
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      if (!part) continue;
      var fields = part.split('&');
      var color = fields[1] ? fields[1] : GREY;

      // 公式：MathQuill 静态域。注意参考实现只给「没有颜色」的条目上灰，
      // 有颜色的条目样式是空的——公式本身永远是白的，颜色只体现在「1:」上。
      var mathDiv = div(null);
      var mathSpan = document.createElement('span');
      mathSpan.className = 'formula';
      if (!fields[1]) mathSpan.style.color = GREY;
      mathSpan.textContent = fields[0];
      mathDiv.appendChild(mathSpan);
      mathDiv.addEventListener('touchstart', click.bind(null, i));
      maths.appendChild(mathDiv);
      MQ.StaticMath(mathSpan);

      // 「N:」：颜色列里的一格
      var legendCell = div('legendContainer');
      var legendSpan = document.createElement('span');
      legendSpan.className = 'legend';
      legendSpan.style.color = color;
      legendSpan.textContent = (i + 1) + ':';
      legendCell.appendChild(legendSpan);
      legendCell.addEventListener('touchstart', click.bind(null, i));
      colors.appendChild(legendCell);

      cells.push({ math: mathSpan, legend: legendCell });
    }

    // 量尺寸：每一格的行高对齐公式高度，整块宽度取「颜色列 + 公式」的最大值 + 10
    var width = 0;
    var height = 0;
    for (var k = 0; k < cells.length; k++) {
      var cell = cells[k];
      var formulaHeight = cell.math.offsetHeight;
      cell.legend.style.height = formulaHeight + 'px';
      cell.legend.style.lineHeight = formulaHeight + 'px';
      height += Math.max(formulaHeight, cell.legend.offsetHeight);
      width = Math.max(width, cell.math.offsetWidth + cell.legend.offsetWidth);
    }
    Android.onLegendComplete(width + 10, height);
  }

  /** 点某一条：让 Android 把那条曲线收起来 / 放出来。 */
  function click(index) {
    Android.onClickLegend(index);
  }

  window.__Legend = {
    setFormulaColor: render
  };
})(window, document);
