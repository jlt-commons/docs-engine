// Adapted from jolt-lang/jolt-lang.github.io@c3b7d98 resources/static/js/search.js (EPL-1.0).
(() => {
  const navSearch = document.getElementById('nav-search');
  const input = document.getElementById('search-input');
  const results = document.getElementById('search-results');
  const toggle = document.getElementById('search-toggle');
  if (!navSearch || !input || !results) return;
  const indexUrl = navSearch.dataset.index;
  const lunrUrl = navSearch.dataset.lunr;

  let index = null;
  let loading = null;
  let documents = null;
  let activeIndex = -1;
  let debounceTimer = null;

  // lunr is ~29 KB and most visitors never search, so it loads on first use.
  let lunrLoading = null;
  const loadLunr = () => {
    if (window.lunr) return Promise.resolve();
    if (lunrLoading) return lunrLoading;
    lunrLoading = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = lunrUrl;
      script.onload = () => resolve();
      script.onerror = () => reject(new Error('could not load ' + lunrUrl));
      document.head.appendChild(script);
    });
    return lunrLoading;
  };

  const loadIndex = () => {
    if (index) return Promise.resolve();
    if (loading) return loading;
    loading = Promise.all([
      loadLunr(),
      fetch(indexUrl).then((response) => {
        if (!response.ok) throw new Error('HTTP ' + response.status);
        return response.json();
      })
    ])
      .then(([, docs]) => {
        documents = docs;
        index = lunr(function () {
          this.ref('href');
          // The page title only scores on the page's own entry; sections get
          // a weaker copy so they still match, but rank below the page.
          this.field('title', { boost: 10, extractor: (doc) => (isPage(doc) ? doc.title : '') });
          this.field('page', { boost: 2, extractor: (doc) => doc.title });
          this.field('heading', { boost: 5 });
          this.field('text');
          docs.forEach((doc) => this.add(doc, { boost: isPage(doc) ? 3 : 1 }));
        });
      });
    return loading;
  };

  const isPage = (doc) => !doc.href.includes('#');

  // Each word matches as a whole (stemmed) word or, more weakly, as a prefix,
  // so results stay put while a word is still being typed. Built with the
  // query API rather than index.search so ':' or '*' in the input can't throw.
  const search = (query) => {
    const terms = lunr.tokenizer(query).map(String);
    return index.query((q) => {
      terms.forEach((term) => {
        q.term(term, { boost: 10 });
        q.term(term, { usePipeline: false, wildcard: lunr.Query.wildcard.TRAILING });
      });
    });
  };

  const snippet = (text, terms) => {
    const clean = text.replace(/\s+/g, ' ').trim();
    const lower = clean.toLowerCase();
    let at = -1;
    for (const term of terms) {
      const i = lower.indexOf(term);
      if (i !== -1) { at = i; break; }
    }
    if (at === -1) return clean.slice(0, 130);
    const start = Math.max(0, at - 40);
    return (start > 0 ? '…' : '') + clean.slice(start, start + 130).trim();
  };

  const hits = () => Array.from(results.querySelectorAll('.search-hit'));

  const clearActive = () => {
    activeIndex = -1;
    hits().forEach((el) => el.classList.remove('active'));
  };

  const setActive = (i) => {
    const elements = hits();
    if (!elements.length) return;
    clearActive();
    activeIndex = (i + elements.length) % elements.length;
    elements[activeIndex].classList.add('active');
    elements[activeIndex].scrollIntoView({ block: 'nearest' });
  };

  const close = () => {
    results.hidden = true;
    clearActive();
    navSearch.classList.remove('open');
  };

  const render = (query, matches) => {
    results.textContent = '';
    if (!query) { results.hidden = true; return; }
    results.hidden = false;
    if (!matches) {
      const message = document.createElement('div');
      message.className = 'search-empty';
      message.textContent = 'Search is unavailable right now.';
      results.appendChild(message);
      return;
    }
    if (!matches.length) {
      const empty = document.createElement('div');
      empty.className = 'search-empty';
      empty.textContent = 'No results for “' + query + '”.';
      results.appendChild(empty);
      return;
    }
    const byHref = {};
    documents.forEach((doc) => { byHref[doc.href] = doc; });
    matches.slice(0, 12).forEach((match) => {
      const doc = byHref[match.ref];
      if (!doc) return;
      const link = document.createElement('a');
      link.className = 'search-hit';
      link.href = doc.href;
      const title = document.createElement('span');
      title.className = 'hit-title';
      title.textContent = doc.title;
      const heading = document.createElement('span');
      heading.className = 'hit-heading';
      heading.textContent = doc.heading;
      const text = document.createElement('span');
      text.className = 'hit-text';
      text.textContent = snippet(doc.text, query.toLowerCase().split(/\s+/));
      link.append(title);
      if (doc.heading !== doc.title) link.append(heading);
      if (doc.text) link.append(text);
      results.appendChild(link);
    });
  };

  const runSearch = (query) => {
    if (!query) { render('', null); return; }
    loadIndex()
      .then(() => render(query, search(query)))
      .catch(() => render(query, null));
  };

  input.addEventListener('input', () => {
    clearTimeout(debounceTimer);
    const query = input.value.trim();
    debounceTimer = setTimeout(() => runSearch(query), 120);
  });

  input.addEventListener('focus', () => {
    const query = input.value.trim();
    if (query) runSearch(query);
  });

  input.addEventListener('keydown', (event) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      if (results.hidden) runSearch(input.value.trim());
      else setActive(activeIndex + 1);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive(activeIndex - 1);
    } else if (event.key === 'Enter') {
      const active = hits()[activeIndex] || hits()[0];
      if (active) {
        event.preventDefault();
        window.location.assign(active.getAttribute('href'));
      }
    } else if (event.key === 'Escape') {
      close();
      input.blur();
    }
  });

  if (toggle) {
    toggle.addEventListener('click', () => {
      const open = navSearch.classList.toggle('open');
      toggle.setAttribute('aria-expanded', String(open));
      if (open) input.focus();
    });
  }

  document.addEventListener('keydown', (event) => {
    if (event.key !== '/' || event.ctrlKey || event.metaKey || event.altKey) return;
    const target = event.target;
    if (target && target.closest('input, textarea, select, [contenteditable]')) return;
    event.preventDefault();
    navSearch.classList.add('open');
    if (toggle) toggle.setAttribute('aria-expanded', 'true');
    input.focus();
    input.select();
  });

  document.addEventListener('click', (event) => {
    if (!navSearch.contains(event.target)) close();
  });

  results.addEventListener('click', (event) => {
    if (event.target.closest('.search-hit')) close();
  });
})();
