/*
 * 历史页。
 *
 * 行为照参考实现那份打包过的页面重写：
 *   * 页面上永远有一个「正在加载中...」的条，三种状态（正在加载 / 没有更多了 /
 *     没有历史记录 / 记录太少直接藏掉）和原版一字不差；
 *   * 滚到接近底部就再要一页，endId 取最后一条记录的 id；
 *   * 点某一条 → 把公式交回 Android（原版也是把公式塞回计算页）；
 *   * Android 那边准备好了会调 window.__History.reset() 让页面重新拉一遍。
 */
(function (window, document) {
  'use strict';

  var ext = window.SuperCalcMQ || {};
  var MQ = ext.MQ || window.MathQuill.getInterface(2);

  var root = document.getElementById('root');
  var listDiv = document.createElement('div');
  listDiv.style.width = '100%';
  root.appendChild(listDiv);

  var loadingOuter = document.createElement('div');
  loadingOuter.className = 'outerDiv';
  var loadingText = document.createElement('span');
  loadingText.className = 'text';
  loadingText.textContent = '正在加载中...';
  loadingOuter.appendChild(loadingText);
  root.appendChild(loadingOuter);

  /** [{date: '2026年9月30日', data: [{id, formula, result}]}] */
  var groups = [];
  var allLoaded = false;
  var loading = false;

  function ctrl() {
    return window.__HistoryCtrl;
  }

  // ---------------------------------------------------------------
  // 公式文本的归一化（和参考实现里那个 m() 一个动作）
  // ---------------------------------------------------------------

  var TEX_ALIASES = {
    '^\\circ': '\\degree',
    '^\\prime': '\\minute',
    '^\\pprime': '\\second',
    '^{\\prime\\prime}': '\\second',
  };

  function reFormat(latex) {
    var text = latex == null ? '' : String(latex);
    text = text.replace(/\^(\\(?:circ|prime|pprime)|\{\\prime\\prime\})/g, function (m) {
      return TEX_ALIASES[m] || m;
    });
    // {度}\degree{分}\minute{秒}\second 合成一个度分秒符号
    return text.replace(
      /{?([\d.]*)}?\\degree{?([\d.]*)}?\\minute{?([\d.]*)}?\\second/g,
      function (m, d, mi, s) {
        return '\\dms{' + d + '}{' + mi + '}{' + s + '}';
      }
    );
  }

  // ---------------------------------------------------------------
  // 渲染
  // ---------------------------------------------------------------

  function renderItem(item, index) {
    var row = document.createElement('div');
    row.className = 'formulaContainer';
    row.id = 'itemDiv' + index;
    row.addEventListener('click', function () {
      var bridge = ctrl();
      if (bridge && bridge.clickFormula) bridge.clickFormula(reFormat(item.formula));
    });

    var formula = document.createElement('span');
    formula.className = 'formulaStyle';
    formula.id = 'formulaDiv' + index;
    formula.textContent = reFormat(item.formula);
    row.appendChild(formula);

    var result = document.createElement('span');
    result.className = 'resultStyle';
    result.id = 'resultDiv' + index;
    // 参考实现连结果串也过一遍同一个归一化函数
    result.textContent = item.result == null ? '' : reFormat(item.result);
    row.appendChild(result);

    return row;
  }

  function renderGroup(group, index) {
    var box = document.createElement('div');
    var title = document.createElement('div');
    title.className = 'title';
    title.textContent = group.date;
    box.appendChild(title);
    for (var i = 0; i < group.data.length; i++) {
      box.appendChild(renderItem(group.data[i], index + '_' + i));
    }
    return box;
  }

  function render() {
    listDiv.innerHTML = '';
    for (var i = 0; i < groups.length; i++) {
      listDiv.appendChild(renderGroup(groups[i], i));
    }
    refreshMath();
  }

  /**
   * 把每个公式/结果 span 交给 MathQuill 静态渲染。
   *
   * 参考实现渲染完还会把 `.newlineArea` 清空并把宽度压成 1px——换行符在历史记录里
   * 只用来分开两行公式，本身不该占位置。
   */
  function refreshMath() {
    var nodes = listDiv.querySelectorAll('.formulaStyle, .resultStyle');
    for (var i = 0; i < nodes.length; i++) {
      try {
        MQ.StaticMath(nodes[i]);
      } catch (e) {
        if (window.console) {
          console.log('StaticMath 失败: ' + e.message);
        }
      }
    }
    var areas = listDiv.querySelectorAll('.newlineArea');
    for (var j = 0; j < areas.length; j++) {
      areas[j].innerHTML = '';
      areas[j].style.width = '1px';
    }
    var spans = listDiv.querySelectorAll('.newlineAreaSpan');
    for (var k = 0; k < spans.length; k++) {
      spans[k].innerHTML = '';
    }
  }

  /** 底部那条的四种状态，和参考实现的 setLoaded 一一对应。 */
  function setLoaded(mode) {
    if (mode === 'none') {
      loadingOuter.style.display = '';
      loadingOuter.style.backgroundColor = 'transparent';
      loadingOuter.style.boxShadow = 'none';
      loadingText.innerHTML = '没有历史记录';
      return;
    }
    if (mode === 'hide') {
      loadingOuter.style.display = 'none';
      return;
    }
    loadingOuter.style.display = '';
    loadingOuter.style.backgroundColor = '';
    loadingOuter.style.boxShadow = '';
    loadingText.innerHTML = mode;
  }

  function countItems() {
    var n = 0;
    for (var i = 0; i < groups.length; i++) n += groups[i].data.length;
    return n;
  }

  function lastId() {
    if (!groups.length) return null;
    var data = groups[groups.length - 1].data;
    if (!data.length) return null;
    return data[data.length - 1].id;
  }

  // ---------------------------------------------------------------
  // 取数据
  // ---------------------------------------------------------------

  function getFormulas() {
    var bridge = ctrl();
    if (!bridge || !bridge.getFormulas || loading || allLoaded) return;
    loading = true;

    var raw = bridge.getFormulas(lastId() === null ? null : String(lastId()));
    var items;
    try {
      items = typeof raw === 'string' ? JSON.parse(raw) : raw;
    } catch (e) {
      if (window.console) console.log('getFormulas 解析失败: ' + raw);
      items = [];
    }

    if (items && items.length) {
      for (var i = 0; i < items.length; i++) {
        var item = items[i];
        if (!groups.length || groups[groups.length - 1].date !== item.date) {
          groups.push({ date: item.date, data: [] });
        }
        groups[groups.length - 1].data.push({
          id: item.id,
          formula: item.formula,
          result: item.result,
        });
      }
      render();
      setLoaded('正在加载中...');
    } else {
      allLoaded = true;
      if (!groups.length) {
        setLoaded('none');
      } else {
        // 记录太少（不到一屏）时干脆不显示这条
        setLoaded(countItems() < 9 ? 'hide' : '没有更多了');
      }
    }
    loading = false;
    // 参考实现是 React 的 componentDidUpdate：一次渲染完，只要内容还没铺满一屏，
    // 就自己接着拉下一页，直到铺满或者没有更多。
    if (!allLoaded) {
      window.setTimeout(function () {
        var doc = document.documentElement;
        var viewport = doc.clientHeight || (window.screen.height - 50);
        if (doc.scrollHeight - 120 <= viewport) getFormulas();
        // MathQuill 是自己在 rAF 里回流的，量高度得等它排完版，不然会把「还没排好」
        // 当成「内容不够一屏」，一路拉到空页去（底栏就会变成「没有更多了」）。
      }, 80);
    }
  }

  window.onscroll = function () {
    var y = window.scrollY;
    var h = document.documentElement.clientHeight;
    var total = document.documentElement.scrollHeight;
    // 和参考实现同一个判断：滚到六成以下、并且离底不到 2000px
    if (y + h > 0.6 * total && y + h + 2000 > total) {
      getFormulas();
    }
  };

  window.__History = {
    reset: function () {
      groups = [];
      allLoaded = false;
      loading = false;
      listDiv.innerHTML = '';
      window.scrollTo(0, 0);
      var bridge = ctrl();
      if (bridge && bridge.resetFinish) bridge.resetFinish();
      getFormulas();
    },
  };

  // 页面挂好之后自己先拉一次（Android 那边 onPageFinished 也会调 reset）
  window.__History.reset();
})(window, document);
