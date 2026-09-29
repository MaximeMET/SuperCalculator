"""原版探针页：裸 MathQuill + 命令表，不带我们的 editor.js。

用法：python bare_probe.py <mathquill.min.js 的 URL> <输出 html>
"""

import sys

import keylist

mq_url, out_path = sys.argv[1], sys.argv[2]

html = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<link rel="stylesheet" href="mathquill/mathquill.css">
<script src="jquery.min.js"></script>
<script src="__MQ_URL__"></script>
</head>
<body>
<span id="probeField"></span>
<script>
__BODY__
</script>
</body>
</html>
"""

html = html.replace("__MQ_URL__", mq_url).replace("__BODY__", keylist.page_js(False))
open(out_path, "w", encoding="utf-8").write(html)
print("wrote", out_path)
