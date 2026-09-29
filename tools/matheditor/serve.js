// 静态文件服务器，根目录指向 APK 的 assets/matheditor，
// 这样浏览器里跑的页面和 WebView 里跑的完全是同一套文件。
const http = require("http");
const fs = require("fs");
const path = require("path");

const root = path.resolve(process.argv[2]);
const port = Number(process.argv[3] || 8777);
// 第二根目录：探针页面放在这里，不用污染要打进 APK 的 assets
const extraRoot = process.argv[4] ? path.resolve(process.argv[4]) : null;

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".woff2": "font/woff2",
  ".woff": "font/woff",
  ".ttf": "font/ttf",
  ".otf": "font/otf",
  ".eot": "application/vnd.ms-fontobject",
  ".svg": "image/svg+xml",
};

http
  .createServer((req, res) => {
    const rel = decodeURIComponent(req.url.split("?")[0]).replace(/^\/+/, "") || "editor.html";
    let file = path.resolve(root, rel);
    if (!fs.existsSync(file) && extraRoot) {
      const alt = path.resolve(extraRoot, rel);
      if (alt.startsWith(extraRoot)) file = alt;
    }
    if (!file.startsWith(root) && !(extraRoot && file.startsWith(extraRoot))) {
      res.writeHead(403).end("forbidden");
      return;
    }
    fs.readFile(file, (err, data) => {
      if (err) {
        res.writeHead(404).end("not found: " + rel);
        return;
      }
      res.writeHead(200, { "Content-Type": TYPES[path.extname(file)] || "application/octet-stream" });
      res.end(data);
    });
  })
  .listen(port, () => console.log(`serving ${root} on http://127.0.0.1:${port}`));
