(function() {
  var warn = function(message) {
    if (console != null && typeof console.warn === 'function') {
      console.warn(message);
    }
  };

  window.t2 = function() {
    if (!window.messages) {
      return window.translate.apply(this, arguments);
    }

    var args = Array.prototype.slice.call(arguments, 0),
        key = args.join('.');
    if (!window.messages[key]) {
      warn('No translation for "' + key + '"');
    }
    return (window.messages && window.messages[key]) || key;
  };

  window.t = function() {
    var args = Array.prototype.slice.call(arguments, 0),
        key = args.join('.'),
        storageKey = 'l10n.' + key,
        message = localStorage.getItem(storageKey);
    if (!message) {
      return window.t2.apply(this, arguments);
    }
    return message;
  };


  window.tp = function() {
    var args = Array.prototype.slice.call(arguments, 0),
        key = args.shift(),
        storageKey = 'l10n.' + key,
        message = localStorage.getItem(storageKey);
    if (!message) {
      message = window.messages[key];
    }
    if (message) {
      args.forEach(function(p, i) {
        message = message.replace('{' + i + '}', p);
      });
    } else {
      warn('No translation for "' + key + '"');
    }
    return message || '';
  };


  window.translate = function() {
    var args = Array.prototype.slice.call(arguments, 0),
        tokens = args.reduce(function(prev, current) {
          return prev.concat(current.split('.'));
        }, []),
        key = tokens.join('.'),
        start = window.SS && window.SS.phrases,
        found = !!start;

    if (found) {
      var result = tokens.reduce(function(prev, current) {
        if (!current || !prev[current]) {
          warn('No translation for "' + key + '"');
          found = false;
        }
        return current ? prev[current] : prev;
      }, start);
    } else {
      warn('No translation for "' + key + '"');
    }

    return found ? result : key;
  };


  window.requestMessages = function() {
    var apiUrl = baseUrl + '/api/l10n/index';
    return jQuery.get(apiUrl, function(bundle) {
      for (var message in bundle) {
        if (bundle.hasOwnProperty(message)) {
          var storageKey = 'l10n.' + message;
          localStorage.setItem(storageKey, bundle[message]);
        }
      }
    });
  };

})();