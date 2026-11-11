/// The "Ask Claude" chat every BLADE editor shares, with blade-chat.css: a
/// purple-ringed box in the top bar that opens one conversation for the page,
/// anchored below the top-right corner, with New, a larger size, and fold
/// (Esc). The box says when an answer is being written and when one arrived
/// while the chat was folded.
///
/// The page supplies only how a message is answered:
///
/// ```js
/// const chat = BladeChat.create({
///   ask: 'Ask Claude about these rules…',      // the folded box
///   title: 'Claude · these rules',
///   intro: 'Ask for a rule, or about one…',     // HTML for an empty chat
///   send: async (text, history, onSteps) => ({ text, steps, meta, actions }),
/// });
/// chat.enable(true);
/// ```
///
/// `history` is the earlier turns, `{role: 'user'|'assistant', text}`;
/// `onSteps(list)` shows progress while the answer is written. The answer's
/// `text` may use the little Markdown the model writes (paragraphs, `-`
/// lists, `code`, **bold**); `meta` is a line under it and `actions` its
/// buttons, `{label, title, run}`. A thrown Error shows as a failed turn.
(function () {
	'use strict';

	function escapeHtml(s) {
		return String(s == null ? '' : s).replace(/[&<>"']/g, c =>
			({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
	}

	/// Escaped first, so nothing but these few forms renders.
	function mdLite(text) {
		const inline = t => escapeHtml(t).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>');
		return String(text || '').trim().split(/\n\s*\n/).map(block => {
			const lines = block.split('\n');
			if (lines.every(l => /^\s*[-*] /.test(l))) {
				return '<ul>' + lines.map(l => '<li>' + inline(l.replace(/^\s*[-*] /, '')) + '</li>').join('') + '</ul>';
			}
			return '<p>' + lines.map(inline).join('<br>') + '</p>';
		}).join('');
	}

	function el(tag, props, kids) {
		const e = Object.assign(document.createElement(tag), props || {});
		(kids || []).forEach(k => e.appendChild(typeof k === 'string' ? document.createTextNode(k) : k));
		return e;
	}

	function create(opts) {
		const state = { messages: [], job: null, unread: false, enabled: false };

		const askText = el('span', { className: 'ai-ask-text', textContent: opts.ask });
		const ask = el('button', { type: 'button', className: 'ai-ask', hidden: true, title: opts.askTitle || opts.ask },
			[el('span', { className: 'ai-spark', textContent: '✦' }), askText]);
		const right = document.querySelector('.vorpal-topbar-right');
		if (right) right.insertBefore(ask, right.firstChild); else document.body.appendChild(ask);

		// The log is rebuilt on every step, so it is not the live region: a
		// screen reader would hear the whole conversation again. `heard` says
		// only what is new (a reply, an error, the work starting).
		const log = el('div', { className: 'ai-log' });
		const heard = el('div', { className: 'ai-sr-only' });
		heard.setAttribute('aria-live', 'polite');
		let heardCount = 0;
		const input = el('textarea', { rows: 2, spellcheck: true,
			placeholder: opts.placeholder || 'Ask a question or ask for a change. Enter sends; Shift+Enter starts a new line.' });
		const send = el('button', { type: 'button', className: 'ai-send', textContent: 'Send' });
		const clear = el('button', { type: 'button', textContent: 'New', title: 'Start a new conversation' });
		input.setAttribute('aria-label', 'Message to ' + (opts.title || 'Claude'));
		const max = el('button', { type: 'button', textContent: '□', title: 'Make the chat larger' });
		max.setAttribute('aria-label', 'Make the chat larger');
		const fold = el('button', { type: 'button', textContent: '–', title: 'Fold the chat away (Esc)' });
		fold.setAttribute('aria-label', 'Fold the chat away');
		const panel = el('section', { className: 'ai-chat', hidden: true }, [
			el('div', { className: 'ai-chat-head' }, [
				el('span', { className: 'ai-chat-title' }, [el('span', { className: 'ai-spark', textContent: '✦ ' }), opts.title || 'Claude']),
				clear, max, fold]),
			log,
			heard,
			el('div', { className: 'ai-chat-foot' }, [input, send]),
		]);
		panel.setAttribute('role', 'dialog');
		panel.setAttribute('aria-label', 'Chat with Claude');
		document.body.appendChild(panel);

		function renderAsk() {
			ask.hidden = !state.enabled;
			ask.classList.toggle('busy', !!state.job);
			ask.classList.toggle('unread', state.unread);
			askText.textContent = state.job ? 'Claude is working…' : state.unread ? 'Claude replied · open' : opts.ask;
		}

		function message(m) {
			const box = el('div', { className: 'ai-msg ' + (m.role === 'user' ? 'user' : 'bot') + (m.error ? ' err' : '') });
			if (m.error) { box.textContent = '✕ ' + m.error; return box; }
			if (m.role === 'user') box.textContent = m.text;
			else box.appendChild(el('div', { className: 'ai-text', innerHTML: mdLite(m.text) }));
			if (m.meta || (m.actions && m.actions.length)) {
				const row = el('div', { className: 'ai-meta' }, m.meta ? [m.meta] : []);
				(m.actions || []).forEach(a => {
					const b = el('button', { type: 'button', textContent: a.label, title: a.title || '' });
					b.addEventListener('click', a.run);
					row.appendChild(b);
				});
				box.appendChild(row);
			}
			if (m.steps && m.steps.length) {
				const n = m.steps.length;
				box.appendChild(el('details', {}, [
					el('summary', { textContent: 'How it was checked (' + n + (n === 1 ? ' step)' : ' steps)') }),
					el('ol', {}, m.steps.map(s => el('li', { textContent: s }))),
				]));
			}
			return box;
		}

		function render() {
			renderAsk();
			log.innerHTML = '';
			if (!state.messages.length && !state.job && opts.intro) {
				log.appendChild(el('div', { className: 'ai-empty', innerHTML: opts.intro }));
			}
			state.messages.forEach(m => log.appendChild(message(m)));
			if (state.job) {
				log.appendChild(el('div', { className: 'ai-msg bot' }, [
					el('div', { textContent: 'Working…' }),
					el('ol', {}, (state.job.steps || []).map(s => el('li', { textContent: s }))),
				]));
			}
			log.scrollTop = log.scrollHeight;
			if (state.messages.length > heardCount) {
				const last = state.messages[state.messages.length - 1];
				if (last.role !== 'user') heard.textContent = (last.error ? 'Error: ' : '') + (last.text || '') + (last.meta ? '. ' + last.meta : '');
				heardCount = state.messages.length;
			} else if (state.messages.length < heardCount) {
				heardCount = state.messages.length;   // a new conversation
			}
			send.disabled = !!state.job;
			send.textContent = state.job ? 'Working…' : 'Send';
		}

		function open() {
			if (!state.enabled) return;
			panel.hidden = false;
			state.unread = false;
			render();
			input.focus();
		}
		function close() {
			panel.hidden = true;
			renderAsk();
			ask.focus();
		}

		async function submit() {
			const text = input.value.trim();
			if (!text || state.job) return;
			// A failed turn is left out whole: the question it failed on was
			// never answered, and sent again it reads as if it had been.
			const history = state.messages.filter((m, i, all) => !m.error && !(m.role === 'user' && all[i + 1] && all[i + 1].error))
				.map(m => ({ role: m.role, text: m.text }));
			state.messages.push({ role: 'user', text: text });
			input.value = '';
			state.job = { steps: ['sending'] };
			const job = state.job;
			render();
			let answer;
			try {
				answer = await opts.send(text, history, steps => {
					if (!state.job) return;
					state.job.steps = steps || [];
					if (!panel.hidden) render(); else renderAsk();
				});
				answer = Object.assign({ role: 'assistant' }, answer, { text: (answer && answer.text) || '(no answer)' });
			} catch (e) {
				answer = { role: 'assistant', text: '', error: (e && e.message) || String(e) };
			}
			if (!state.job || state.job !== job) return;   // reset while it was out
			state.job = null;
			state.messages.push(answer);
			if (panel.hidden) state.unread = true;
			render();
		}

		ask.addEventListener('click', open);
		fold.addEventListener('click', close);
		max.addEventListener('click', () => {
			panel.classList.toggle('max');
			const big = panel.classList.contains('max');
			max.textContent = big ? '❐' : '□';
			max.title = big ? 'Restore the chat to its corner' : 'Make the chat larger';
		});
		clear.addEventListener('click', () => { if (!state.job) { state.messages = []; render(); } });
		send.addEventListener('click', submit);
		input.addEventListener('keydown', e => {
			if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); submit(); }
		});
		// Esc folds the chat, and only that: the page's own Esc (clearing a
		// filter, say) does not also run.
		panel.addEventListener('keydown', e => { if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); } });

		return {
			enable(on) { state.enabled = !!on; if (!on) panel.hidden = true; renderAsk(); },
			open: open,
			close: close,
			/// A new conversation (the page moved to something else). An answer
			/// still being written is let go; it is not shown when it comes.
			reset() { state.messages = []; state.job = null; state.unread = false; render(); },
			busy: () => !!state.job,
			messages: () => state.messages,
		};
	}

	window.BladeChat = { create: create, mdLite: mdLite };
})();
