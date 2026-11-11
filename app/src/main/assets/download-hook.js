(function () {
  if (window.__gifAppHook) return;
  window.__gifAppHook = true;

  var SLICE = 256 * 1024;
  var CHAR_STEP = 8192;

  function toBase64Chunks(bytes) {
    var chunks = [];
    for (var offset = 0; offset < bytes.length; offset += SLICE) {
      var part = bytes.subarray(offset, Math.min(bytes.length, offset + SLICE));
      var binary = "";
      for (var index = 0; index < part.length; index += CHAR_STEP) {
        var piece = part.subarray(index, Math.min(part.length, index + CHAR_STEP));
      var copy = new Array(piece.length);
      for (var n = 0; n < piece.length; n += 1) copy[n] = piece[n];
      binary += String.fromCharCode.apply(null, copy);
      }
      chunks.push(btoa(binary));
    }
    return chunks;
  }

  function sendBlob(blob, filename) {
    if (!window.GifDownload) return Promise.reject(new Error("download bridge missing"));
    return blob.arrayBuffer().then(function (buffer) {
      var bytes = new Uint8Array(buffer);
      var chunks = toBase64Chunks(bytes);
      GifDownload.start(filename || "animation.gif", blob.type || "image/gif", bytes.length);
      for (var i = 0; i < chunks.length; i += 1) GifDownload.chunk(chunks[i]);
      GifDownload.finish();
    });
  }

  var saving = false;
  window.__gifSaveBlobUrl = function (url, filename) {
    if (saving) return Promise.resolve();
    saving = true;
    return fetch(url).then(function (response) {
      return response.blob();
    }).then(function (blob) {
      return sendBlob(blob, filename || "animation.gif");
    }).catch(function (error) {
      if (window.GifDownload) GifDownload.fail(String(error && error.message ? error.message : error));
    }).then(function () {
      saving = false;
    });
  };

  document.addEventListener("click", function (event) {
    var target = event.target;
    if (!target || !target.closest) return;
    var link = target.closest("a");
    if (!link) return;
    var href = link.href || "";
    var name = link.getAttribute("download") || "animation.gif";
    if (href.indexOf("blob:") === 0) {
      event.preventDefault();
      event.stopPropagation();
      window.__gifSaveBlobUrl(href, name);
      return;
    }
    if (href.indexOf("data:") === 0 && link.hasAttribute("download") && window.GifDownload) {
      event.preventDefault();
      event.stopPropagation();
      GifDownload.saveDataUrl(name, href);
    }
  }, true);
})();
