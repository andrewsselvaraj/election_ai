// Voice search: adds a mic button after every <input data-voice>.
// Uses the browser's built-in speech recognition (Chrome, Edge, Safari); where that is
// missing (e.g. Firefox) no button is shown and the search box works as before.
//   data-voice="submit"  -> submit the input's form when the user stops speaking
//   data-voice           -> just fill the box (and fire "input" so live search updates)
//   data-voice-lang      -> recognition language (default en-IN)
(function () {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) return;

    const style = document.createElement('style');
    style.textContent = `
        .voice-btn { flex: none; display: inline-flex; align-items: center; justify-content: center;
                     width: 42px; height: 42px; padding: 0; border-radius: 50%; cursor: pointer;
                     border: 1px solid var(--accent, #0b6e4f); background: transparent; color: var(--accent, #0b6e4f); }
        .voice-btn:hover { background: var(--accent-soft, #e6f3ee); }
        .voice-btn svg { width: 20px; height: 20px; }
        .voice-btn.listening { background: #c62828; border-color: #c62828; color: #fff;
                               animation: voice-pulse 1.2s ease-in-out infinite; }
        @keyframes voice-pulse { 0%,100% { box-shadow: 0 0 0 0 rgba(198,40,40,.5); }
                                 50% { box-shadow: 0 0 0 8px rgba(198,40,40,0); } }
        @media (prefers-reduced-motion: reduce) { .voice-btn.listening { animation: none; } }`;
    document.head.appendChild(style);

    const MIC = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" '
        + 'stroke-linejoin="round" aria-hidden="true"><rect x="9" y="2" width="6" height="12" rx="3"/>'
        + '<path d="M5 10v1a7 7 0 0 0 14 0v-1"/><line x1="12" y1="18" x2="12" y2="22"/></svg>';

    document.querySelectorAll('input[data-voice]').forEach(input => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'voice-btn';
        btn.title = 'Search by voice';
        btn.setAttribute('aria-label', 'Search by voice');
        btn.innerHTML = MIC;
        input.insertAdjacentElement('afterend', btn);

        let rec = null;
        btn.addEventListener('click', () => {
            if (rec) { rec.stop(); return; }   // second click stops listening

            rec = new SR();
            rec.lang = input.dataset.voiceLang || 'en-IN';
            rec.interimResults = true;          // show words as they are spoken
            rec.maxAlternatives = 1;
            let heard = '';

            rec.onresult = e => {
                let text = '';
                for (const r of e.results) text += r[0].transcript;
                text = text.replace(/[.?!,]+$/, '').trim();   // drop trailing punctuation
                input.value = text;
                input.dispatchEvent(new Event('input', { bubbles: true }));
                if (e.results[e.results.length - 1].isFinal) heard = text;
            };
            rec.onerror = e => {
                if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
                    alert('Microphone access is blocked. Allow the microphone for this site to search by voice.');
                }
            };
            rec.onend = () => {
                btn.classList.remove('listening');
                btn.title = 'Search by voice';
                rec = null;
                if (heard && input.dataset.voice === 'submit' && input.form) {
                    input.form.requestSubmit ? input.form.requestSubmit() : input.form.submit();
                }
            };

            btn.classList.add('listening');
            btn.title = 'Listening… click to stop';
            rec.start();
        });
    });
})();
