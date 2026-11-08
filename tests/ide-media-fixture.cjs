// Manual integration fixture: open the printed URL in Reel Break's IDE Setup dialog.
// Retry video should replace the invalid source with the supplied H.264 MP4.
const http = require('node:http');
const fs = require('node:fs');
const sample = fs.readFileSync(process.argv[2] || 'build/media-smoke/h264-aac.mp4');
const html = `<!doctype html><meta charset="utf-8"><title>Reel playback regression</title>
<body style="background:#171717;color:white;font:18px sans-serif">
<h2>IDE codec and startup regression</h2>
<video id="video" controls playsinline loop style="width:600px;height:340px" src="/unsupported.bin">
<source src="/video.mp4" type="video/mp4"></video>
<p id="status">Loading</p><p id="support"></p>
<button id="fail">Fail codec again</button>
<script>
const video = document.getElementById('video');
document.getElementById('support').textContent = 'H.264 support: ' + video.canPlayType('video/mp4; codecs="avc1.42E01E"');
document.getElementById('fail').onclick = () => {
    video.src = '/unsupported.bin'; video.load(); video.play().catch(() => {});
};
setInterval(() => {
    document.getElementById('status').textContent = 'time=' + video.currentTime.toFixed(1) +
        ' paused=' + video.paused + ' muted=' + video.muted + ' error=' + (video.error?.code || 0) +
        ' hidden=' + window.__reelBreakMediaState?.hidden;
}, 200);
video.play().catch(() => {});
</script>`;
const server = http.createServer((request, response) => {
    if (request.url === '/unsupported.bin') {
        response.writeHead(200, {'Content-Type': 'video/mp4'});
        response.end('Intentionally invalid media');
    } else if (request.url === '/video.mp4') {
        const range = /^bytes=(\d+)-(\d*)$/.exec(request.headers.range || '');
        const start = range ? Number(range[1]) : 0;
        const end = range?.[2] ? Math.min(Number(range[2]), sample.length - 1) : sample.length - 1;
        if (start > end || start >= sample.length) {
            response.writeHead(416, {'Content-Range': `bytes */${sample.length}`});
            response.end();
            return;
        }
        response.writeHead(range ? 206 : 200, {
            'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes', 'Content-Length': end - start + 1,
            ...(range ? {'Content-Range': `bytes ${start}-${end}/${sample.length}`} : {})
        });
        response.end(sample.subarray(start, end + 1));
    } else {
        response.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'});
        response.end(html);
    }
});
server.listen(0, '127.0.0.1', () => console.log(`Open in IDE Setup: http://127.0.0.1:${server.address().port}/`));
