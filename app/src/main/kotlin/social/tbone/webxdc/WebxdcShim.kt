package social.tbone.webxdc

/**
 * The `webxdc.js` the player injects into every mini app.
 *
 * It implements the messenger side of https://webxdc.org/docs/spec/api.html
 * on top of a single native entry point, [BRIDGE_NAME].call(method, id, json).
 * Native answers through `__tboneXdcResolve(id, ok, resultJson, error)` and
 * pushes new updates through `__tboneXdcDeliver(update)`.
 *
 * Deliberately absent: any network or peer-to-peer transport. T-Bone runs xdcs
 * single-player, so updates are journalled on-device and replayed with the
 * spec's serial numbers; `joinRealtimeChannel()` exists but is a local no-op so
 * apps that probe for it keep working instead of crashing.
 */
object WebxdcShim {

    const val BRIDGE_NAME = "TBoneXdc"

    fun build(selfAddr: String, selfName: String): String = """
(function () {
  if (window.webxdc) return;

  var listener = null;
  var nextId = 1;
  var pending = {};

  function nativeCall(method, params) {
    var id = nextId++;
    return new Promise(function (resolve, reject) {
      pending[id] = { resolve: resolve, reject: reject };
      try {
        window.$BRIDGE_NAME.call(method, id, JSON.stringify(params || {}));
      } catch (e) {
        delete pending[id];
        reject(e);
      }
    });
  }

  window.__tboneXdcResolve = function (id, ok, resultJson, error) {
    var entry = pending[id];
    if (!entry) return;
    delete pending[id];
    if (ok) {
      var value = null;
      if (resultJson !== null && resultJson !== undefined && resultJson !== "") {
        try { value = JSON.parse(resultJson); } catch (e) { value = resultJson; }
      }
      entry.resolve(value);
    } else {
      entry.reject(new Error(error || "webxdc error"));
    }
  };

  window.__tboneXdcDeliver = function (update) {
    if (typeof listener !== "function") return;
    try {
      listener(update);
    } catch (e) {
      console.error("webxdc update listener failed:", e);
    }
  };

  function base64ToBytes(base64) {
    var raw = atob(base64);
    var bytes = new Uint8Array(raw.length);
    for (var i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    return bytes;
  }

  function bytesToBase64(bytes) {
    var chunk = "";
    for (var i = 0; i < bytes.length; i++) chunk += String.fromCharCode(bytes[i]);
    return btoa(chunk);
  }

  window.webxdc = {
    selfAddr: ${jsString(selfAddr)},
    selfName: ${jsString(selfName)},
    isAppSender: true,
    isBroadcast: false,
    sendUpdateInterval: 1000,
    sendUpdateMaxSize: 128000,

    sendUpdate: function (update, descr) {
      update = update || {};
      return nativeCall("sendUpdate", {
        payload: update.payload === undefined ? null : update.payload,
        info: update.info,
        document: update.document,
        summary: update.summary,
        href: update.href,
        notify: update.notify
      });
    },

    setUpdateListener: function (callback, serial) {
      listener = callback;
      return nativeCall("setUpdateListener", { serial: serial || 0 });
    },

    getAllUpdates: function () {
      return nativeCall("getAllUpdates", {});
    },

    sendToChat: function (message) {
      var msg = message || {};
      var flat = { text: msg.text };
      var file = msg.file;
      if (file) {
        if (typeof file.base64 === "string") {
          flat.file = { name: file.name, base64: file.base64 };
          return nativeCall("sendToChat", flat);
        }
        if (typeof file.plainText === "string") {
          flat.file = { name: file.name, plainText: file.plainText };
          return nativeCall("sendToChat", flat);
        }
        if (file.blob) {
          return file.blob.arrayBuffer().then(function (buffer) {
            flat.file = {
              name: file.name,
              base64: bytesToBase64(new Uint8Array(buffer))
            };
            return nativeCall("sendToChat", flat);
          });
        }
        return Promise.reject(new Error("sendToChat: file has no blob, base64 or plainText"));
      }
      return nativeCall("sendToChat", flat);
    },

    importFiles: function (filter) {
      return nativeCall("importFiles", filter || {}).then(function (files) {
        return (files || []).map(function (f) {
          var bytes = base64ToBytes(f.base64 || "");
          return new File([bytes], f.name || "file", { type: f.type || "" });
        });
      });
    },

    joinRealtimeChannel: function () {
      // Single-player player: no peers exist. Keep the API surface so apps that
      // feature-detect it do not crash; sends are accepted and dropped locally.
      var joined = true;
      var channel = {
        setListener: function (callback) {
          if (!joined) throw new Error("channel has been left");
        },
        send: function (data) {
          if (!joined) throw new Error("channel has been left");
        },
        leave: function () {
          joined = false;
          try { window.$BRIDGE_NAME.call("realtimeLeave", nextId++, "{}"); } catch (e) {}
        }
      };
      try { window.$BRIDGE_NAME.call("realtimeJoin", nextId++, "{}"); } catch (e) {}
      return channel;
    }
  };
})();
""".trimIndent()

    private fun jsString(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                '&' -> sb.append("\\u0026")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
