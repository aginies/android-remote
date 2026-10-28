package com.example.remoteandroid.server

import android.graphics.Bitmap
import com.example.remoteandroid.SessionStatus
import com.example.remoteandroid.capture.MjpegFrameSource
import com.example.remoteandroid.control.RemoteControl
import com.example.remoteandroid.settings.StreamSettings
import com.example.remoteandroid.util.Network
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext

private const val BOUNDARY = "remoteandroid"
private const val PORT = 8443

class WebServer(
    sslContext: SSLContext,
    private val frameSource: MjpegFrameSource,
    private val settingsProvider: () -> StreamSettings,
    private val onSettingsUpdate: (StreamSettings) -> Unit,
    private val token: String,
    private val onWake: () -> Unit,
) : NanoHTTPD(null, PORT) {
    private val clientCount = AtomicInteger(0)
    private val startedAt = System.currentTimeMillis()

    @Volatile
    private var lastServedBytes = 0L

    @Volatile
    private var lastServedAt = 0L

    init {
        makeSecure(sslContext.serverSocketFactory, arrayOf("TLSv1.2", "TLSv1.3"))
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/" -> {
                if (authenticated(session)) servePage() else serveLoginPage()
            }

            uri == "/login" && session.method == Method.POST -> {
                handleLogin(session)
            }

            !authenticated(session) -> {
                newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized\n")
            }

            uri == "/stream.mjpg" -> {
                serveStream(session)
            }

            uri == "/status.json" -> {
                serveStatus()
            }

            uri == "/qr.png" -> {
                serveQr()
            }

            uri == "/settings" && session.method == Method.POST -> {
                serveSettingsPost(session)
            }

            uri == "/settings" -> {
                serveSettingsGet()
            }

            uri == "/control" && session.method == Method.POST -> {
                serveControlPost(session)
            }

            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found\n")
            }
        }
    }

    private fun authenticated(session: IHTTPSession): Boolean = token.isEmpty() || tokenMatches(cookieToken(session))

    private fun tokenMatches(submitted: String?): Boolean {
        if (submitted.isNullOrEmpty()) return false
        return java.security.MessageDigest.isEqual(token.toByteArray(), submitted.toByteArray())
    }

    private fun cookieToken(session: IHTTPSession): String? {
        val header =
            session.headers.entries
                .firstOrNull { it.key.equals("cookie", ignoreCase = true) }
                ?.value ?: return null
        for (part in header.split(';')) {
            val kv = part.trim().split('=', limit = 2)
            if (kv.size == 2 && kv[0].trim() == "token") return kv[1].trim()
        }
        return null
    }

    private fun handleLogin(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (_: Exception) {
            return serveLoginPage("Bad request")
        }
        val submitted = session.parms["token"]?.trim().orEmpty()
        if (token.isNotEmpty() && tokenMatches(submitted)) {
            val res =
                newFixedLengthResponse(
                    Response.Status.REDIRECT_SEE_OTHER,
                    MIME_HTML,
                    "<html><body>Redirecting...</body></html>",
                )
            res.addHeader("Location", "/")
            res.addHeader("Set-Cookie", "token=$token; Path=/; HttpOnly; Secure; SameSite=Strict")
            return res
        }
        return serveLoginPage("Wrong token — try again")
    }

    private fun serveLoginPage(error: String? = null): Response {
        val errorHtml = if (error != null) "<p class=\"err\">$error</p>" else ""
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            LOGIN_HTML.replace("__ERROR__", errorHtml),
        )
    }

    private fun servePage(): Response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", HTML)

    private fun serveStream(session: IHTTPSession): Response {
        val ip = session.remoteIpAddress ?: "unknown"
        SessionStatus.connect(ip)
        clientCount.incrementAndGet()
        val stream =
            frameSource.newStream(BOUNDARY) {
                clientCount.decrementAndGet()
                SessionStatus.disconnect(ip)
            }
        // Chunked transfer-encoding stays enabled on purpose: NanoHTTPD only
        // writes a Content-Length when chunkedTransfer is false, and a live
        // stream has no known length (it would emit "Content-Length: -1", which
        // browsers reject). Browsers de-chunk transparently before parsing the
        // multipart parts, so MJPEG renders correctly.
        val res =
            newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=$BOUNDARY",
                stream,
            )
        res.setGzipEncoding(false)
        res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        res.addHeader("Pragma", "no-cache")
        res.addHeader("Expires", "0")
        return res
    }

    private fun serveStatus(): Response {
        val s = settingsProvider()
        val json =
            JSONObject().apply {
                put("fps", frameSource.framesPerSecond())
                put("width", s.width)
                put("fpsSetting", s.fps)
                put("quality", s.quality)
                put("clients", clientCount.get())
                put("clientIps", JSONArray(SessionStatus.clients()))
                put("control", RemoteControl.isConnected)
                val served = frameSource.servedBytes()
                val nowMs = System.currentTimeMillis()
                val bandwidthKBs =
                    if (lastServedAt > 0 && nowMs > lastServedAt) {
                        (served - lastServedBytes) * 1000.0 / (nowMs - lastServedAt) / 1024.0
                    } else {
                        0.0
                    }
                lastServedBytes = served
                lastServedAt = nowMs
                put("bandwidthKBs", bandwidthKBs)
                put("encodeKBs", frameSource.encodedBytesPerSecond() / 1024.0)
                put("totalServedBytes", served)
                put("uptimeSec", (nowMs - startedAt) / 1000)
                put("urls", JSONArray(Network.localIPv4s().map { ip -> "https://$ip:$PORT/" }))
            }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun serveSettingsGet(): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            settingsProvider().toJson().toString(),
        )

    private fun serveSettingsPost(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (_: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                "{\"error\":\"bad request\"}",
            )
        }
        val body = files["postData"] ?: ""
        val s = StreamSettings.fromJson(body)
        onSettingsUpdate(s)
        return newFixedLengthResponse(Response.Status.OK, "application/json", s.toJson().toString())
    }

    private fun serveControlPost(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (_: Exception) {
            return controlResponse(false, "bad request")
        }
        val o =
            try {
                JSONObject(files["postData"] ?: "")
            } catch (_: Exception) {
                return controlResponse(false, "bad json")
            }
        if (o.optString("type") == "wake") {
            onWake()
            return controlResponse(true, null)
        }
        if (!RemoteControl.isConnected) {
            return controlResponse(false, "remote control not enabled on the phone")
        }
        // Wake the screen (no-op if already awake) before injecting input, so a
        // click on the stream also wakes a sleeping phone.
        onWake()
        val ok =
            when (o.optString("type")) {
                "tap" -> {
                    RemoteControl.tap(o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat())
                    true
                }

                "swipe" -> {
                    RemoteControl.swipe(
                        o.optDouble("x1", 0.0).toFloat(),
                        o.optDouble("y1", 0.0).toFloat(),
                        o.optDouble("x2", 1.0).toFloat(),
                        o.optDouble("y2", 1.0).toFloat(),
                        o.optLong("duration", 300).coerceAtLeast(16),
                    )
                    true
                }

                "key" -> {
                    RemoteControl.key(o.optString("key"))
                    true
                }

                "text" -> {
                    RemoteControl.type(o.optString("text"))
                    true
                }

                else -> {
                    false
                }
            }
        return controlResponse(ok, if (ok) null else "unknown control type")
    }

    private fun controlResponse(
        ok: Boolean,
        error: String?,
    ): Response {
        val json =
            JSONObject().apply {
                put("ok", ok)
                if (error != null) put("error", error)
            }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun serveQr(): Response {
        val bitmap = generateQr(viewerUrl())
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bitmap.recycle()
        val bytes = baos.toByteArray()
        return newFixedLengthResponse(
            Response.Status.OK,
            "image/png",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
    }

    private fun viewerUrl(): String {
        val ip = Network.localIPv4s().firstOrNull() ?: "127.0.0.1"
        return "https://$ip:$PORT/"
    }

    private fun generateQr(
        text: String,
        size: Int = 360,
    ): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}

private const val LOGIN_HTML: String = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>RemoteAndroid Login</title>
<style>
html,body{margin:0;height:100%;background:#0f1419;color:#e6edf3;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;display:flex;align-items:center;justify-content:center}
.box{width:320px;max-width:90vw;background:#1a2029;border:1px solid #26303a;border-radius:10px;padding:28px}
h1{font-size:20px;margin:0 0 4px}
p{color:#8b98a5;font-size:13px;margin:0 0 16px}
.err{color:#e5484d;font-size:13px;margin:0 0 12px}
input{width:100%;box-sizing:border-box;background:#0f1419;color:#e6edf3;border:1px solid #303b47;border-radius:6px;padding:10px;font-size:16px;font-family:monospace;letter-spacing:2px}
button{width:100%;margin-top:12px;background:#1e7b50;color:#fff;border:none;border-radius:6px;padding:10px;font-size:15px;font-weight:600;cursor:pointer}
</style>
</head>
<body>
<form class="box" method="post" action="/login">
<h1>RemoteAndroid</h1>
<p>Enter the access token shown on the phone.</p>
__ERROR__
<input name="token" type="text" autofocus autocomplete="off" placeholder="Token" spellcheck="false">
<button type="submit">Connect</button>
</form>
</body>
</html>
"""

private const val HTML: String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>RemoteAndroid</title>
<style>
html,body{margin:0;height:100%;background:#0f1419;color:#e6edf3;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;overflow:hidden;display:flex;flex-direction:column}
#stage{flex:1 1 auto;position:relative;min-height:0;background:#000}
#frame{position:absolute;top:0;left:0;width:100%;height:100%;object-fit:contain;background:#000;touch-action:none;-webkit-user-drag:none;-moz-user-select:none;-webkit-user-select:none;user-select:none}
*{-webkit-user-select:none;user-select:none}
#bar{flex:0 0 auto;background:rgba(26,32,41,.95);border-bottom:1px solid #26303a;z-index:10}
.row{display:flex;align-items:center;gap:12px;padding:8px 14px;flex-wrap:wrap}
.row+.row{border-top:1px solid #202936}
#title{font-weight:600;color:#e6edf3;text-decoration:none}
#url{color:#8b98a5;font-size:12px;font-family:monospace}
#stats{color:#8b98a5;font-size:12px;margin-left:auto;font-variant-numeric:tabular-nums}
#conn{display:flex;align-items:center;gap:6px;font-size:12px;color:#8b98a5}
#conn .dot{width:10px;height:10px;border-radius:50%;background:#e5484d}
#conn.on .dot{background:#2ecc71}
button{background:#212b36;color:#e6edf3;border:1px solid #303b47;border-radius:6px;padding:6px 10px;cursor:pointer;font-size:13px}
button:hover{background:#2b3743}
input,select{background:#1a2029;color:#e6edf3;border:1px solid #303b47;border-radius:6px;padding:5px 8px;font-size:13px}
input[type=text]{width:220px}
input[type=range]{accent-color:#2ecc71;background:transparent;border:none;padding:0}
#panel{display:none;flex-wrap:wrap;gap:14px;align-items:center;padding:10px 14px;border-top:1px solid #26303a;font-size:13px}
#panel.open{display:flex}
label{color:#8b98a5;display:flex;align-items:center;gap:6px}
.c{font-size:12px;color:#8b98a5}
#hint{color:#5c6b79;font-size:12px;margin-left:auto}
#apply,#send{background:#1e7b50;border-color:#1e7b50;font-weight:600}
#ripple{position:fixed;width:0;height:0;border:2px solid #2ecc71;border-radius:50%;pointer-events:none;opacity:0;z-index:30;transform:translate(-50%,-50%);transition:width .15s,height .15s,opacity .3s}
</style>
</head>
<body>
<div id="bar">
  <div class="row">
    <button id="gear" title="Settings (?)">⚙</button>
    <a id="title" href="/">RemoteAndroid</a>
    <span id="url"></span>
    <span id="conn"><span class="dot"></span><span id="connlabel">…</span></span>
    <span id="stats">connecting…</span>
  </div>
  <div class="row">
    <button id="back" title="Back">⬅︎</button>
    <button id="home" title="Home">🏠</button>
    <button id="recents" title="Recents">▤</button>
    <button id="wake" title="Wake screen">☀</button>
    <input id="text" type="text" placeholder="Type text, then Send">
    <button id="send">Send</button>
    <span id="hint"></span>
  </div>
  <div id="panel">
    <label>Preset
      <select id="preset">
        <option value="low">Low</option>
        <option value="medium" selected>Medium</option>
        <option value="high">High</option>
        <option value="custom">Custom</option>
      </select>
    </label>
    <label>Width <select id="width"></select></label>
    <label>FPS <input id="fps" type="range" min="5" max="30" step="1"><span class="c" id="fpsv"></span></label>
    <label>Quality <input id="quality" type="range" min="30" max="95" step="1"><span class="c" id="qv"></span></label>
    <button id="apply">Apply</button>
  </div>
</div>
<div id="stage">
  <img id="frame" src="/stream.mjpg" alt="stream" draggable="false">
</div>
<div id="ripple"></div>
<script>
var cfg = { width: 960, fps: 15, quality: 75 };
var widths = [640, 960, 1280, 9999];
var presets = {
  low:    { width: 640,  fps: 8,  quality: 55 },
  medium: { width: 960,  fps: 15, quality: 75 },
  high:   { width: 1280, fps: 20, quality: 85 }
};
var controlOn = false;
function el(id){ return document.getElementById(id); }
function clamp01(v){ return Math.max(0, Math.min(1, v)); }
function hint(t){ el('hint').textContent = t; }
function sendControl(o){
  fetch('/control', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(o) })
    .then(function(r){ return r.json(); })
    .then(function(res){ if (res && res.ok === false) hint(res.error || 'control unavailable'); })
    .catch(function(){ hint('control request failed'); });
}
function contentRect(img){
  var r = img.getBoundingClientRect();
  var nw = img.naturalWidth, nh = img.naturalHeight;
  if (!nw || !nh) return { left:r.left, top:r.top, width:r.width, height:r.height };
  var s = Math.min(r.width/nw, r.height/nh);
  var w = nw*s, h = nh*s;
  return { left: r.left + (r.width-w)/2, top: r.top + (r.height-h)/2, width: w, height: h };
}
function render(){
  var w = el('width');
  w.innerHTML = '';
  for (var i = 0; i < widths.length; i++) {
    var o = document.createElement('option');
    o.value = widths[i];
    o.textContent = (widths[i] === 9999) ? 'Source' : (widths[i] + 'px');
    if (widths[i] === cfg.width) o.selected = true;
    w.appendChild(o);
  }
  el('fps').value = cfg.fps;
  el('quality').value = cfg.quality;
  el('fpsv').textContent = cfg.fps;
  el('qv').textContent = cfg.quality + '%';
}
function apply(){
  cfg.width = parseInt(el('width').value, 10);
  cfg.fps = parseInt(el('fps').value, 10);
  cfg.quality = parseInt(el('quality').value, 10);
  el('preset').value = 'custom';
  fetch('/settings', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(cfg) })
    .then(function(r){ return r.json(); })
    .then(function(s){ cfg.width = s.width; cfg.fps = s.fps; cfg.quality = s.quality; render(); });
}
var connected = false;
function fmtRate(kbs){
  if (!kbs) kbs = 0;
  return (kbs < 1024) ? kbs.toFixed(0) + ' KB/s' : (kbs / 1024).toFixed(1) + ' MB/s';
}
function setConn(on){
  connected = on;
  var c = el('conn');
  if (on) { c.classList.add('on'); el('connlabel').textContent = 'Connected'; }
  else { c.classList.remove('on'); el('connlabel').textContent = 'Disconnected'; }
}
function updateUiState(){
  var eff = connected && controlOn;
  var ids = ['back','home','recents','send'];
  for (var i = 0; i < ids.length; i++) el(ids[i]).disabled = !eff;
  el('text').disabled = !eff;
  el('wake').disabled = !connected;
  el('frame').style.cursor = eff ? 'crosshair' : 'default';
  if (!connected) hint('disconnected from server');
  else if (!controlOn) hint('remote control disabled — enable it on the phone (Accessibility)');
  else hint('tap/drag to interact · wheel to scroll');
}
function stats(){
  fetch('/status.json').then(function(r){ return r.json(); }).then(function(s){
    setConn(true);
    controlOn = (s.control === true);
    el('url').textContent = s.urls.join('  ');
    el('stats').textContent = s.fps + ' fps · ' + s.width + 'px · ' + s.clients + ' viewer(s) · ' + fmtRate(s.bandwidthKBs) + ' · ' + (controlOn ? 'remote ON' : 'remote OFF');
    updateUiState();
  }).catch(function(){ setConn(false); updateUiState(); });
}
function ripple(x, y){
  var r = el('ripple');
  r.style.left = x + 'px'; r.style.top = y + 'px';
  r.style.width = '36px'; r.style.height = '36px'; r.style.opacity = '1';
  setTimeout(function(){ r.style.width='0'; r.style.height='0'; r.style.opacity='0'; }, 160);
}
var frame = el('frame');
var dragging = false, sx = 0, sy = 0, pid = 0;
frame.addEventListener('pointerdown', function(e){
  // Cancel the browser's native selection (the blue overlay): selection is
  // initiated on pointerdown, so preventDefault() here keeps the interaction
  // limited to sending actions to the remote device.
  e.preventDefault();
  dragging = true; pid = e.pointerId;
  var c = contentRect(frame);
  sx = clamp01((e.clientX - c.left) / c.width);
  sy = clamp01((e.clientY - c.top) / c.height);
  if (frame.setPointerCapture) { try { frame.setPointerCapture(e.pointerId); } catch (err) {} }
  ripple(e.clientX, e.clientY);
});
frame.addEventListener('pointerup', function(e){
  if (!dragging) return;
  dragging = false;
  var c = contentRect(frame);
  var ex = clamp01((e.clientX - c.left) / c.width);
  var ey = clamp01((e.clientY - c.top) / c.height);
  var dx = (ex - sx) * c.width, dy = (ey - sy) * c.height;
  if (Math.sqrt(dx*dx + dy*dy) < 10) {
    sendControl({ type:'tap', x: ex, y: ey });
  } else {
    sendControl({ type:'swipe', x1: sx, y1: sy, x2: ex, y2: ey, duration: 300 });
  }
});
frame.addEventListener('wheel', function(e){
  e.preventDefault();
  var c = contentRect(frame);
  var cx = clamp01((e.clientX - c.left) / c.width);
  var cy = clamp01((e.clientY - c.top) / c.height);
  var dir = (e.deltaY > 0) ? -1 : 1;
  sendControl({ type:'swipe', x1: cx, y1: cy, x2: cx, y2: clamp01(cy + dir * 0.25), duration: 200 });
}, { passive: false });
el('back').onclick = function(){ sendControl({ type:'key', key:'back' }); };
el('home').onclick = function(){ sendControl({ type:'key', key:'home' }); };
el('recents').onclick = function(){ sendControl({ type:'key', key:'recents' }); };
el('wake').onclick = function(){ sendControl({ type:'wake' }); };
el('send').onclick = function(){ var t = el('text').value; if (t) { sendControl({ type:'text', text: t }); el('text').value = ''; } };
el('text').addEventListener('keydown', function(e){ if (e.key === 'Enter') el('send').onclick(); });
el('gear').onclick = function(){ el('panel').classList.toggle('open'); };
document.addEventListener('keydown', function(e){ if (e.key === '?') el('panel').classList.toggle('open'); });
el('fps').oninput = function(){ el('fpsv').textContent = el('fps').value; };
el('quality').oninput = function(){ el('qv').textContent = el('quality').value + '%'; };
el('preset').onchange = function(){
  var p = presets[this.value];
  if (p) { cfg.width = p.width; cfg.fps = p.fps; cfg.quality = p.quality; render(); }
};
el('apply').onclick = apply;
fetch('/settings').then(function(r){ return r.json(); }).then(function(s){
  widths = s.widths || widths;
  cfg.width = s.width; cfg.fps = s.fps; cfg.quality = s.quality;
  render();
});
setInterval(stats, 1000);
stats();
</script>
<p style="text-align:center;color:#5c6b79;font-size:12px;padding:6px">RemoteAndroid — by Antoine Giniès</p>
</body>
</html>
"""
