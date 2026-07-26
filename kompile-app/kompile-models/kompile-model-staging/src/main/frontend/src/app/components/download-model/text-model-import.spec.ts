import {
  buildTextAssetMap,
  buildTextAssetUrlMap,
  HTTPS_COMPONENT_SOURCE,
  invalidTextAssetUrls,
  isPinnedHuggingFaceRevision,
  missingLocalTextBundle,
  missingRemoteTextBundle,
  normalizeHuggingFaceReference,
  normalizeHuggingFaceRepositoryId,
  normalizePublicComponentUrl,
  parseModelStagingPrefill,
  restoreImportDraft,
  retainSelectionOnPickerCancel,
  serializeImportDraft,
  TextBundleFiles
} from './text-model-import';

describe('text model import flow', () => {
  const file = (name: string, body = '{}') =>
    new File([body], name, { type: 'application/octet-stream' });

  it('accepts a complete local tokenizer-config bundle', () => {
    const files: TextBundleFiles = {
      model: file('model.gguf', 'GGUF'),
      tokenizer: file('tokenizer.json'),
      tokenizer_config: file('tokenizer_config.json'),
      model_config: file('config.json')
    };

    expect(missingLocalTextBundle(files)).toEqual([]);
  });

  it('reports the missing tokenizer configuration path', () => {
    const files: TextBundleFiles = {
      model: file('model.gguf', 'GGUF'),
      tokenizer: file('tokenizer.json'),
      model_config: file('config.json')
    };

    expect(missingLocalTextBundle(files)).toEqual([
      'tokenizer_config.json or chat_template.jinja'
    ]);
  });

  it('keeps optional sidecars optional when canonical semantics are present', () => {
    const files: TextBundleFiles = {
      model: file('model.ggml', 'GGML'),
      tokenizer: file('tokenizer.json'),
      chat_template: file('chat_template.jinja', '{{ messages }}'),
      text_generation: file('text-generation.json')
    };

    expect(missingLocalTextBundle(files)).toEqual([]);
    expect(files.special_tokens_map).toBeUndefined();
    expect(files.added_tokens).toBeUndefined();
    expect(files.generation_config).toBeUndefined();
  });

  it('retains a prior file when the native picker is cancelled', () => {
    const tokenizer = file('tokenizer.json');

    expect(retainSelectionOnPickerCancel(tokenizer, undefined)).toBe(tokenizer);
  });

  it('persists paths and target choices without secrets or File handles', () => {
    const serialized = serializeImportDraft({
      source: 'huggingface',
      repository: 'Qwen/model',
      revision: 'a'.repeat(40),
      modelPath: 'weights/model.gguf',
      hfToken: 'hf_secret',
      model: file('model.gguf')
    });

    expect(serialized).toContain('weights/model.gguf');
    expect(serialized).not.toContain('hf_secret');
    expect(serialized).not.toContain('model.gguf","size');
  });

  it('accepts canonical Hugging Face repositories and public URLs without credentials', () => {
    expect(normalizeHuggingFaceRepositoryId(' Qwen/model ')).toBe('Qwen/model');
    expect(normalizeHuggingFaceReference('https://www.huggingface.co/Qwen/model/'))
      .toBe('https://huggingface.co/Qwen/model');
    expect(normalizeHuggingFaceReference(
      'https://huggingface.co/Qwen/model/blob/main/model-Q4_K_M.gguf'
    )).toBe('https://huggingface.co/Qwen/model/blob/main/model-Q4_K_M.gguf');
    expect(normalizeHuggingFaceReference(
      'https://huggingface.co/Qwen/model?token=secret'
    )).toBeUndefined();
    expect(normalizeHuggingFaceReference(
      'https://user:secret@huggingface.co/Qwen/model'
    )).toBeUndefined();
    expect(normalizeHuggingFaceReference('Qwen/model/resolve/main')).toBeUndefined();
    expect(normalizeHuggingFaceReference('http://huggingface.co/Qwen/model')).toBeUndefined();
    expect(normalizeHuggingFaceReference('/tmp/model.gguf')).toBeUndefined();

    const canonical = JSON.parse(serializeImportDraft({
      source: 'huggingface',
      repository: 'https://www.huggingface.co/Qwen/model/'
    }));
    expect(canonical.fields.repository).toBe('https://huggingface.co/Qwen/model');

    const signed = JSON.parse(serializeImportDraft({
      source: 'huggingface',
      repository: 'https://huggingface.co/Qwen/model?token=secret'
    }));
    expect(signed.fields.repository).toBeUndefined();

    const local = JSON.parse(serializeImportDraft({
      source: 'local',
      repository: '/tmp/model.gguf'
    }));
    expect(local.fields.repository).toBeUndefined();

    expect(restoreImportDraft(JSON.stringify({
      version: 1,
      fields: {
        source: 'huggingface',
        repository: 'https://user:secret@huggingface.co/Qwen/model'
      }
    }))).toBeNull();
  });

  it('restores a valid draft and rejects corrupt or unknown versions', () => {
    const serialized = serializeImportDraft({
      source: 'local',
      format: 'gguf',
      targetProfile: 'android-arm64-nnapi-accelerator'
    });

    expect(restoreImportDraft(serialized)).toEqual({
      source: 'local',
      format: 'gguf',
      targetProfile: 'android-arm64-nnapi-accelerator'
    });
    expect(restoreImportDraft('{broken')).toBeNull();
    expect(restoreImportDraft('{"version":99,"fields":{}}')).toBeNull();
  });

  it('builds canonical repository-path and individual component URL DTOs', () => {
    expect(buildTextAssetMap({
      modelPath: ' weights/model.gguf ',
      tokenizerPath: 'tokenizer.json',
      tokenizerConfigPath: 'tokenizer_config.json',
      modelConfigPath: 'config.json',
      generationConfigPath: ''
    })).toEqual({
      model: 'weights/model.gguf',
      tokenizer: 'tokenizer.json',
      tokenizerConfig: 'tokenizer_config.json',
      modelConfig: 'config.json'
    });
    expect(buildTextAssetUrlMap({
      modelUrl: 'https://models.example/chat-Q4_K_M.gguf',
      tokenizerUrl: 'https://models.example/tokenizer.json',
      tokenizerConfigUrl: 'https://models.example/tokenizer_config.json',
      modelConfigUrl: 'https://models.example/config.json',
      addedTokensUrl: ''
    })).toEqual({
      model: 'https://models.example/chat-Q4_K_M.gguf',
      tokenizer: 'https://models.example/tokenizer.json',
      tokenizerConfig: 'https://models.example/tokenizer_config.json',
      modelConfig: 'https://models.example/config.json'
    });
    expect(invalidTextAssetUrls({
      modelUrl: 'https://models.example/model.gguf?token=secret',
      tokenizerUrl: 'http://models.example/tokenizer.json'
    })).toEqual(['model URL', 'tokenizer.json URL']);
    expect(normalizePublicComponentUrl('https://models.example/config.json'))
      .toBe('https://models.example/config.json');
    expect(normalizePublicComponentUrl('https://user:secret@models.example/config.json'))
      .toBeUndefined();

    // Kept as a compatibility helper; branches are now resolved and pinned server-side.
    expect(isPinnedHuggingFaceRevision('a'.repeat(40))).toBeTrue();
    expect(isPinnedHuggingFaceRevision('main')).toBeFalse();
  });

  it('parses the Android no-network browser handoff fragment', () => {
    const reference = 'https://huggingface.co/Qwen/model';
    expect(parseModelStagingPrefill(`#hf=${encodeURIComponent(reference)}`)).toEqual({
      source: 'huggingface',
      repository: reference,
      outputFormat: 'model',
      modelType: 'llm_ggml',
      format: 'gguf'
    });
    expect(parseModelStagingPrefill(
      '#hf=https%3A%2F%2Fhuggingface.co%2FQwen%2Fmodel%3Ftoken%3Dsecret'
    )).toBeNull();
  });

  it('parses a complete one-shot HTTPS component handoff', () => {
    const modelUrl = 'https://models.example/chat-Q4_K_M.gguf';
    const tokenizerUrl = 'https://models.example/tokenizer.json';
    const tokenizerConfigUrl = 'https://models.example/tokenizer_config.json';
    const modelConfigUrl = 'https://models.example/config.json';
    const fragment = new URLSearchParams({
      modelUrl,
      tokenizerUrl,
      tokenizerConfigUrl,
      modelConfigUrl,
      generationConfigUrl: 'https://models.example/generation_config.json'
    }).toString();

    expect(parseModelStagingPrefill(`#${fragment}`)).toEqual({
      source: HTTPS_COMPONENT_SOURCE,
      repository: '',
      outputFormat: 'model',
      modelType: 'llm_ggml',
      format: 'gguf',
      modelId: 'chat-q4_k_m',
      modelUrl,
      tokenizerUrl,
      tokenizerConfigUrl,
      modelConfigUrl,
      generationConfigUrl: 'https://models.example/generation_config.json'
    });
    expect(missingRemoteTextBundle({
      modelUrl,
      tokenizerUrl,
      chatTemplateUrl: 'https://models.example/chat_template.jinja',
      textGenerationUrl: 'https://models.example/text-generation.json'
    })).toEqual([]);
  });

  it('fails closed on mixed, incomplete, ambiguous, or signed handoff fragments', () => {
    const complete = new URLSearchParams({
      modelUrl: 'https://models.example/chat.gguf',
      tokenizerUrl: 'https://models.example/tokenizer.json',
      tokenizerConfigUrl: 'https://models.example/tokenizer_config.json',
      modelConfigUrl: 'https://models.example/config.json'
    });
    const mixed = new URLSearchParams(complete);
    mixed.set('hf', 'Qwen/model');
    expect(parseModelStagingPrefill(`#${mixed.toString()}`)).toBeNull();

    complete.delete('tokenizerUrl');
    expect(parseModelStagingPrefill(`#${complete.toString()}`)).toBeNull();
    expect(parseModelStagingPrefill(
      '#modelUrl=https%ZZmodels.example%2Fchat.gguf'
    )).toBeNull();
    expect(parseModelStagingPrefill(
      '#modelUrl=https%3A%2F%2Fmodels.example%2Fchat.gguf%3Ftoken%3Dsecret'
    )).toBeNull();
    expect(parseModelStagingPrefill(
      '#modelUrl=https%3A%2F%2Fuser%3Asecret%40models.example%2Fchat.gguf'
    )).toBeNull();
    expect(parseModelStagingPrefill(
      '#hf=Qwen%2Fmodel&hf=Other%2Fmodel'
    )).toBeNull();

    const ambiguousConfig = new URLSearchParams({
      modelUrl: 'https://models.example/chat.gguf',
      tokenizerUrl: 'https://models.example/tokenizer.json',
      tokenizerConfigUrl: 'https://models.example/tokenizer_config.json',
      configUrl: 'https://models.example/config.json',
      modelConfigUrl: 'https://models.example/config.json'
    });
    expect(parseModelStagingPrefill(`#${ambiguousConfig.toString()}`)).toBeNull();
  });

  it('keeps repository discovery separate from exact multiple-model selection', () => {
    const repository = parseModelStagingPrefill('#hf=Qwen%2Fmulti-gguf');
    expect(repository?.['source']).toBe('huggingface');
    expect(repository?.['repository']).toBe('Qwen/multi-gguf');
    expect(repository?.['modelUrl']).toBeUndefined();

    const selected = new URLSearchParams({
      modelUrl: 'https://cdn.example/chat-Q8_0.gguf',
      tokenizerUrl: 'https://cdn.example/tokenizer.json',
      chatTemplateUrl: 'https://cdn.example/chat_template.jinja',
      modelConfigUrl: 'https://cdn.example/config.json'
    });
    expect(parseModelStagingPrefill(`#${selected.toString()}`)?.['modelUrl'])
      .toBe('https://cdn.example/chat-Q8_0.gguf');
  });

  it('never persists transient public component URLs or URL credentials', () => {
    const serialized = serializeImportDraft({
      source: HTTPS_COMPONENT_SOURCE,
      modelUrl: 'https://models.example/model.gguf',
      tokenizerUrl: 'https://models.example/tokenizer.json?token=secret',
      tokenizerConfigUrl: 'https://user:secret@models.example/tokenizer_config.json'
    });
    const restored = restoreImportDraft(serialized);

    expect(serialized).not.toContain('https://models.example/model.gguf');
    expect(serialized).not.toContain('token=secret');
    expect(serialized).not.toContain('user:secret');
    expect(restored?.['source']).toBe(HTTPS_COMPONENT_SOURCE);
    expect(restored?.['modelUrl']).toBeUndefined();
    expect(restored?.['tokenizerUrl']).toBeUndefined();
    expect(restored?.['tokenizerConfigUrl']).toBeUndefined();
  });
});
