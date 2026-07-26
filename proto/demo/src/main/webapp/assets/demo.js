/* BLADE Demo Launcher &mdash; shared front-end behavior.
   No plumbing yet: intercept the form, reveal the storyboard, and make it
   plain that this is a mockup. Swap blade.launch() for a real POST later. */
(function (w, d) {
  var blade = {
    launch: function (e) {
      if (e) e.preventDefault();
      var stage = d.getElementById('stage');
      if (stage) {
        stage.classList.add('live');
        stage.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      }
      blade.toast('This is a mockup - the SIP plumbing is not wired up yet.');
      return false;
    },
    toast: function (msg) {
      var t = d.getElementById('blade-toast');
      if (!t) {
        t = d.createElement('div');
        t.id = 'blade-toast';
        t.className = 'toast';
        d.body.appendChild(t);
      }
      t.textContent = msg;
      t.classList.add('show');
      clearTimeout(blade._tt);
      blade._tt = setTimeout(function () { t.classList.remove('show'); }, 3600);
    }
  };
  w.blade = blade;
})(window, document);
