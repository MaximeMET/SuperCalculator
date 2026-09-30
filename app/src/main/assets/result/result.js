/*
 * 结果页的桥。原版暴露的是 window.__Result.{setResult,setProcess,closeTip}，
 * 这里保持同一组接口名，只是内部换成 MathJax v3。
 */
(function () {
    'use strict';

    var pending = null;

    function texOf(value) {
        return typeof value === 'string' ? value : '';
    }

    function render(id, tex, display) {
        var host = document.getElementById(id);
        host.innerHTML = '';
        if (!tex) {
            return;
        }
        var node = MathJax.tex2svg(tex, { display: display });
        host.appendChild(node);
    }

    function setTip(text) {
        var box = document.getElementById('tip');
        var content = document.getElementById('tipContent');
        if (!text) {
            box.classList.add('hidden');
            content.classList.remove('open');
            content.innerHTML = '';
            return;
        }
        box.classList.remove('hidden');
        content.innerHTML = text;
    }

    function setResult(formula, method, result, tip) {
        pending = function () {
            render('formula', texOf(formula), true);
            render('result', texOf(result), true);
            document.getElementById('method').textContent = texOf(method);
            setTip(texOf(tip));
        };
        if (MathJax.startup && MathJax.startup.promise) {
            MathJax.startup.promise.then(function () {
                if (pending === null) {
                    return;
                }
                var run = pending;
                pending = null;
                run();
            });
        }
    }

    window.__Result = {
        setResult: setResult,
        // 过程展示依赖已经下线的服务器，原版在这里注入过程 HTML，我们保留空实现。
        setProcess: function () {},
        closeTip: function () {
            setTip('');
        }
    };

    document.addEventListener('DOMContentLoaded', function () {
        var mark = document.getElementById('tipMark');
        if (mark) {
            mark.addEventListener('click', function () {
                document.getElementById('tipContent').classList.toggle('open');
            });
        }
    });
})();
