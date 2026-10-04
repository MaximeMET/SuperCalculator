/*
 * 结果页的桥。原版暴露的是 window.__Result.{setResult,setProcess,closeTip}，
 * 这里保持同一组接口名，只是内部换成 MathJax v3。
 */
(function () {
    'use strict';

    var pending = null;
    var pendingProcess = null;

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

    /*
     * 解题过程。数据由计算页离线算好（SolveSteps）：
     *   {steps: [{order, key, label, lines: ["latex", "T:纯文本", ...]}, ...]}
     * 布局照原版 result.min.js：左侧深灰圆角序号 + 标签 + 内容，
     * 上方一条「解决过程」横线。
     */
    function renderProcess(payload) {
        var box = document.getElementById('process');
        var host = document.getElementById('processSteps');
        if (!box || !host) {
            return;
        }
        host.innerHTML = '';
        var steps = payload && payload.steps ? payload.steps : [];
        if (!steps.length) {
            box.classList.add('hidden');
            return;
        }
        box.classList.remove('hidden');
        steps.forEach(function (step) {
            var row = document.createElement('div');
            row.className = 'process-step';
            var order = document.createElement('span');
            order.className = 'process-order';
            order.textContent = step.order;
            row.appendChild(order);
            var body = document.createElement('div');
            body.className = 'process-body';
            var label = document.createElement('span');
            label.className = 'process-label';
            label.textContent = step.label;
            body.appendChild(label);
            (step.lines || []).forEach(function (line) {
                var lineBox = document.createElement('div');
                lineBox.className = 'process-line';
                if (line.indexOf('T:') === 0) {
                    lineBox.classList.add('process-text');
                    lineBox.textContent = line.slice(2);
                } else {
                    try {
                        lineBox.appendChild(MathJax.tex2svg(line, { display: false }));
                    } catch (e) {
                        lineBox.textContent = line;
                    }
                }
                body.appendChild(lineBox);
            });
            row.appendChild(body);
            host.appendChild(row);
        });
    }

    function setProcess(data) {
        pendingProcess = function () {
            renderProcess(data);
        };
        if (MathJax.startup && MathJax.startup.promise) {
            MathJax.startup.promise.then(function () {
                if (pendingProcess === null) {
                    return;
                }
                var run = pendingProcess;
                pendingProcess = null;
                run();
            });
        }
    }

    window.__Result = {
        setResult: setResult,
        setProcess: setProcess,
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
