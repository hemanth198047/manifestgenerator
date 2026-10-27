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
  function addSource(name, text) {
    var blocks = holder.querySelectorAll('.source');
    for (var i = 0; i < blocks.length; i++) {
      var n = blocks[i].querySelector('input[name=name]').value;
      var t = blocks[i].querySelector('textarea[name=text]').value;
      if (n === '' && t === '') { holder.removeChild(blocks[i]); }
    }
    var div = document.createElement('div');
    div.className = 'source';
    var input = document.createElement('input');
    input.name = 'name';
    input.value = name;
    var area2 = document.createElement('textarea');
    area2.name = 'text';
    area2.rows = 8;
    area2.spellcheck = false;
    area2.value = text;
    div.appendChild(input);
    div.appendChild(area2);
    holder.appendChild(div);
  }
  if (holder && files) {
    files.addEventListener('change', function () {
      Array.prototype.forEach.call(files.files, function (f) {
        var reader = new FileReader();
        reader.onload = function () { addSource(f.name, reader.result); };
        reader.readAsText(f, 'UTF-8');
      });
    });
  }
  if (holder && samples) {
    samples.addEventListener('click', function () {
      ['sample-schema.sql', 'sample-dictionary.csv', 'sample-notes.md', 'sample-openapi.json'].forEach(function (n) {
        fetch('/static/samples/' + n).then(function (r) { return r.text(); }).then(function (t) { addSource(n, t); });
      });
    });
  }
});
