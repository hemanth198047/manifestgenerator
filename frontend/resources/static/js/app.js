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
});
