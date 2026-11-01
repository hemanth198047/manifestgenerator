// Optional conveniences only; the application works without JavaScript except file loading and the sample button.
document.addEventListener('DOMContentLoaded', function () {
  var area = document.getElementById('manifest');
  var file = document.getElementById('file');
  var sample = document.getElementById('loadSample');
  if (file && area) {
    file.addEventListener('change', function () {
      var f = file.files[0];
      if (!f) { return; }
      var reader = new FileReader();
      reader.onload = function () { area.value = reader.result; };
      reader.readAsText(f, 'UTF-8');
    });
  }
  if (sample && area) {
    sample.addEventListener('click', function () {
      fetch('/static/samples/sample-manifest.json')
        .then(function (r) { return r.text(); })
        .then(function (t) { area.value = t; });
    });
  }

  var holder = document.getElementById('sources');
  var files = document.getElementById('sourceFiles');
  var samples = document.getElementById('loadSampleSources');
  var binary = /\.(xlsx|xlsm|xls|docx|pdf)$/i;
  function toBase64(buffer) {
    var bytes = new Uint8Array(buffer), chunks = [], step = 0x8000;
    for (var i = 0; i < bytes.length; i += step) {
      chunks.push(String.fromCharCode.apply(null, bytes.subarray(i, i + step)));
    }
    return btoa(chunks.join(''));
  }
  function addSource(name, text, base64) {
    var blocks = holder.querySelectorAll('.source');
    for (var i = 0; i < blocks.length; i++) {
      var n = blocks[i].querySelector('input[name=name]').value;
      var t = blocks[i].querySelector('[name=text]').value;
      if (n === '' && t === '') { holder.removeChild(blocks[i]); }
    }
    var div = document.createElement('div');
    div.className = 'source';
    var input = document.createElement('input');
    input.name = 'name';
    input.value = name;
    div.appendChild(input);
    var textField;
    if (base64) {
      textField = document.createElement('input');
      textField.type = 'hidden';
      var note = document.createElement('span');
      note.className = 'muted';
      note.textContent = 'Binary file (' + Math.floor(base64.length * 3 / 4) + ' bytes)';
      textField.name = 'text';
      textField.value = '';
      div.appendChild(textField);
      div.appendChild(note);
    } else {
      textField = document.createElement('textarea');
      textField.name = 'text';
      textField.rows = 8;
      textField.spellcheck = false;
      textField.value = text;
      div.appendChild(textField);
    }
    var data = document.createElement('input');
    data.type = 'hidden';
    data.name = 'data';
    data.value = base64 || '';
    div.appendChild(data);
    holder.appendChild(div);
  }
  if (holder && files) {
    files.addEventListener('change', function () {
      Array.prototype.forEach.call(files.files, function (f) {
        var reader = new FileReader();
        if (binary.test(f.name)) {
          reader.onload = function () { addSource(f.name, '', toBase64(reader.result)); };
          reader.readAsArrayBuffer(f);
        } else {
          reader.onload = function () { addSource(f.name, reader.result, ''); };
          reader.readAsText(f, 'UTF-8');
        }
      });
    });
  }
  if (holder && samples) {
    samples.addEventListener('click', function () {
      ['sample-schema.sql', 'sample-dictionary.csv', 'sample-notes.md', 'sample-openapi.json'].forEach(function (n) {
        fetch('/static/samples/' + n).then(function (r) { return r.text(); }).then(function (t) { addSource(n, t, ''); });
      });
    });
  }

  var copy = document.getElementById('copyBtn');
  var preview = document.getElementById('previewText');
  if (copy && preview && navigator.clipboard) {
    copy.addEventListener('click', function () {
      navigator.clipboard.writeText(preview.textContent).then(function () { copy.textContent = 'Copied'; });
    });
  }
  var projectFile = document.getElementById('projectFile');
  var projectText = document.getElementById('projectText');
  if (projectFile && projectText) {
    projectFile.addEventListener('change', function () {
      var f = projectFile.files[0];
      if (!f) { return; }
      var reader = new FileReader();
      reader.onload = function () { projectText.value = reader.result; };
      reader.readAsText(f, 'UTF-8');
    });
  }
});
