// Replaces the page's speechSynthesis, which doesn't work in WebView, with one backed by Android's
// TextToSpeech through the transmogrifierSpeech web message listener (SpeechBridge.kt).
(() => {
  const utterances = new Map(); // id → utterance, in speaking order
  let nextId = 1;
  let current = 0;
  let startedAt = 0;
  let voices = [];
  let listening = false;
  let voicesRequested = false;

  const fire = (target, event) => {
    const handler = target['on' + event.type];
    if (typeof handler === 'function') handler.call(target, event);
    target.dispatchEvent(event);
  };

  const finish = (id, type, error) => {
    const utterance = utterances.get(id);
    if (!utterance) return;
    utterances.delete(id);
    if (current === id) current = 0;
    const init = { utterance, elapsedTime: performance.now() - startedAt, error };
    fire(utterance, type === 'end'
      ? new SpeechSynthesisEvent('end', init)
      : new SpeechSynthesisErrorEvent('error', init));
  };

  const onMessage = (event) => {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch (e) {
      return;
    }
    switch (message.type) {
      case 'voices':
        voices = message.voices.map((v) => new SpeechSynthesisVoice(v));
        fire(synthesis, new Event('voiceschanged'));
        break;
      case 'start': {
        const utterance = utterances.get(message.id);
        if (!utterance) break;
        current = message.id;
        startedAt = performance.now();
        fire(utterance, new SpeechSynthesisEvent('start', { utterance }));
        break;
      }
      case 'boundary': {
        const utterance = utterances.get(message.id);
        if (!utterance) break;
        fire(utterance, new SpeechSynthesisEvent('boundary', {
          utterance,
          charIndex: message.charIndex,
          charLength: message.charLength,
          elapsedTime: performance.now() - startedAt,
          name: 'word'
        }));
        break;
      }
      case 'end':
      case 'error':
        finish(message.id, message.type, message.error);
        break;
    }
  };

  const send = (message) => {
    const channel = window.transmogrifierSpeech;
    if (!channel) return false;
    if (!listening) {
      channel.addEventListener('message', onMessage);
      listening = true;
    }
    channel.postMessage(JSON.stringify(message));
    return true;
  };

  const requestVoices = () => {
    if (!voicesRequested) voicesRequested = send({ type: 'voices' });
  };

  const number = (value, fallback) => (Number.isFinite(Number(value)) ? Number(value) : fallback);

  class SpeechSynthesisEvent extends Event {
    constructor(type, init = {}) {
      super(type, init);
      this.utterance = init.utterance;
      this.charIndex = init.charIndex || 0;
      this.charLength = init.charLength || 0;
      this.elapsedTime = init.elapsedTime || 0;
      this.name = init.name || '';
    }
  }

  class SpeechSynthesisErrorEvent extends SpeechSynthesisEvent {
    constructor(type, init = {}) {
      super(type, init);
      this.error = init.error;
    }
  }

  class SpeechSynthesisVoice {
    constructor(v) {
      this.voiceURI = v.name;
      this.name = v.name;
      this.lang = v.lang;
      this.localService = v.localService;
      this.default = v.default;
      Object.freeze(this);
    }
  }

  class SpeechSynthesisUtterance extends EventTarget {
    constructor(text) {
      super();
      this.text = text === undefined ? '' : String(text);
      this.lang = '';
      this.voice = null;
      this.volume = 1;
      this.rate = 1;
      this.pitch = 1;
      for (const type of ['start', 'end', 'error', 'pause', 'resume', 'mark', 'boundary']) {
        this['on' + type] = null;
      }
    }
  }

  class SpeechSynthesis extends EventTarget {
    constructor() {
      super();
      this.onvoiceschanged = null;
    }

    get speaking() {
      return utterances.size > 0;
    }

    get pending() {
      return utterances.size > (current ? 1 : 0);
    }

    // TextToSpeech can't pause, so pause() and resume() do nothing.
    get paused() {
      return false;
    }

    pause() {}

    resume() {}

    getVoices() {
      requestVoices();
      return voices.slice();
    }

    speak(utterance) {
      if (!(utterance instanceof SpeechSynthesisUtterance)) {
        throw new TypeError("Failed to execute 'speak': parameter 1 is not a SpeechSynthesisUtterance.");
      }
      const id = nextId++;
      utterances.set(id, utterance);
      const sent = send({
        type: 'speak',
        id,
        text: String(utterance.text),
        lang: String(utterance.lang || ''),
        voice: utterance.voice ? String(utterance.voice.name) : '',
        rate: number(utterance.rate, 1),
        pitch: number(utterance.pitch, 1),
        volume: number(utterance.volume, 1)
      });
      if (!sent) setTimeout(() => finish(id, 'error', 'synthesis-unavailable'), 0);
    }

    cancel() {
      if (utterances.size === 0) return;
      const canceled = [...utterances];
      const speaking = current;
      utterances.clear();
      current = 0;
      send({ type: 'cancel' });
      // Events fire later, as in browsers, so a handler that speaks again doesn't run inside cancel().
      setTimeout(() => {
        for (const [id, utterance] of canceled) {
          const error = id === speaking ? 'interrupted' : 'canceled';
          fire(utterance, new SpeechSynthesisErrorEvent('error', { utterance, error }));
        }
      }, 0);
    }
  }

  const synthesis = new SpeechSynthesis();
  const globals = {
    SpeechSynthesis,
    SpeechSynthesisUtterance,
    SpeechSynthesisEvent,
    SpeechSynthesisErrorEvent,
    SpeechSynthesisVoice
  };
  for (const [name, value] of Object.entries(globals)) {
    Object.defineProperty(window, name, { configurable: true, writable: true, value });
  }
  Object.defineProperty(window, 'speechSynthesis', {
    configurable: true,
    enumerable: true,
    get: () => synthesis
  });
  requestVoices();
})();
