import {
  TextModelAssetKey,
  TextModelAssetMap,
  TextModelAssetUrlMap
} from '../../models/api-models';

export type TextBundleFiles = Partial<Record<TextModelAssetKey, File>>;

const DRAFT_VERSION = 3;
export const HTTPS_COMPONENT_SOURCE = 'https-components';
const HUGGING_FACE_REPOSITORY_ID =
  /^[A-Za-z0-9][A-Za-z0-9._-]{0,95}\/[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/;
const HUGGING_FACE_HOSTS = new Set(['huggingface.co', 'www.huggingface.co']);
const HUGGING_FACE_ROUTES = new Set(['tree', 'blob', 'resolve']);
const DRAFT_FIELDS = [
  'source',
  'repository',
  'modelId',
  'modelType',
  'format',
  'outputFormat',
  'targetProfile',
  'quantizationProfile',
  'targetSoc',
  'autoPromote',
  'revision',
  'modelPath',
  'tokenizerPath',
  'tokenizerConfigPath',
  'specialTokensMapPath',
  'addedTokensPath',
  'chatTemplatePath',
  'generationConfigPath',
  'modelConfigPath',
  'textGenerationPath'
] as const;

const COMPONENT_FRAGMENT_FIELDS = new Map<string, string>([
  ['modelUrl', 'modelUrl'],
  ['tokenizerUrl', 'tokenizerUrl'],
  ['tokenizerConfigUrl', 'tokenizerConfigUrl'],
  ['specialTokensMapUrl', 'specialTokensMapUrl'],
  ['addedTokensUrl', 'addedTokensUrl'],
  ['chatTemplateUrl', 'chatTemplateUrl'],
  ['generationConfigUrl', 'generationConfigUrl'],
  ['modelConfigUrl', 'modelConfigUrl'],
  ['configUrl', 'modelConfigUrl'],
  ['textGenerationUrl', 'textGenerationUrl']
]);

export const TEXT_BUNDLE_PARTS: TextModelAssetKey[] = [
  'model',
  'tokenizer',
  'tokenizer_config',
  'special_tokens_map',
  'added_tokens',
  'chat_template',
  'generation_config',
  'model_config',
  'text_generation'
];

export function buildTextAssetMap(value: Record<string, unknown>): TextModelAssetMap {
  return compactAssets({
    model: text(value['modelPath']),
    tokenizer: text(value['tokenizerPath']),
    tokenizerConfig: text(value['tokenizerConfigPath']),
    specialTokensMap: text(value['specialTokensMapPath']),
    addedTokens: text(value['addedTokensPath']),
    chatTemplate: text(value['chatTemplatePath']),
    generationConfig: text(value['generationConfigPath']),
    modelConfig: text(value['modelConfigPath']),
    textGeneration: text(value['textGenerationPath'])
  });
}

export function buildTextAssetUrlMap(
  value: Record<string, unknown>
): TextModelAssetUrlMap {
  return compactAssets({
    model: normalizePublicComponentUrl(value['modelUrl']),
    tokenizer: normalizePublicComponentUrl(value['tokenizerUrl']),
    tokenizerConfig: normalizePublicComponentUrl(value['tokenizerConfigUrl']),
    specialTokensMap: normalizePublicComponentUrl(value['specialTokensMapUrl']),
    addedTokens: normalizePublicComponentUrl(value['addedTokensUrl']),
    chatTemplate: normalizePublicComponentUrl(value['chatTemplateUrl']),
    generationConfig: normalizePublicComponentUrl(value['generationConfigUrl']),
    modelConfig: normalizePublicComponentUrl(value['modelConfigUrl']),
    textGeneration: normalizePublicComponentUrl(value['textGenerationUrl'])
  });
}

export function invalidTextAssetUrls(value: Record<string, unknown>): string[] {
  const invalid: string[] = [];
  const fields: Array<[string, string]> = [
    ['modelUrl', 'model URL'],
    ['tokenizerUrl', 'tokenizer.json URL'],
    ['tokenizerConfigUrl', 'tokenizer_config.json URL'],
    ['specialTokensMapUrl', 'special_tokens_map.json URL'],
    ['addedTokensUrl', 'added_tokens.json URL'],
    ['chatTemplateUrl', 'chat_template.jinja URL'],
    ['generationConfigUrl', 'generation_config.json URL'],
    ['modelConfigUrl', 'config.json URL'],
    ['textGenerationUrl', 'text-generation.json URL']
  ];
  for (const [field, label] of fields) {
    if (text(value[field]) && !normalizePublicComponentUrl(value[field])) {
      invalid.push(label);
    }
  }
  return invalid;
}

export function missingRemoteTextBundle(value: Record<string, unknown>): string[] {
  const missing: string[] = [];
  if (!normalizePublicComponentUrl(value['modelUrl'])) {
    missing.push('GGUF/GGML model URL');
  }
  if (!normalizePublicComponentUrl(value['tokenizerUrl'])) {
    missing.push('tokenizer.json URL');
  }
  if (!normalizePublicComponentUrl(value['tokenizerConfigUrl'])
      && !normalizePublicComponentUrl(value['chatTemplateUrl'])) {
    missing.push('tokenizer_config.json or chat_template.jinja URL');
  }
  if (!normalizePublicComponentUrl(value['modelConfigUrl'])
      && !normalizePublicComponentUrl(value['textGenerationUrl'])) {
    missing.push('config.json or text-generation.json URL');
  }
  return missing;
}

export function missingLocalTextBundle(files: TextBundleFiles): string[] {
  const missing: string[] = [];
  if (!usable(files.model)) {
    missing.push('model');
  }
  if (!usable(files.tokenizer)) {
    missing.push('tokenizer.json');
  }
  if (!usable(files.tokenizer_config) && !usable(files.chat_template)) {
    missing.push('tokenizer_config.json or chat_template.jinja');
  }
  if (!usable(files.model_config) && !usable(files.text_generation)) {
    missing.push('config.json or text-generation.json');
  }
  return missing;
}

export function isPinnedHuggingFaceRevision(revision: unknown): boolean {
  return typeof revision === 'string' && /^[0-9a-f]{40,64}$/i.test(revision.trim());
}

export function normalizeHuggingFaceReference(reference: unknown): string | undefined {
  const normalized = text(reference);
  if (!normalized) {
    return undefined;
  }
  if (HUGGING_FACE_REPOSITORY_ID.test(normalized)) {
    return normalized;
  }

  let url: URL;
  try {
    url = new URL(normalized);
  } catch {
    return undefined;
  }
  if (url.protocol !== 'https:'
      || !HUGGING_FACE_HOSTS.has(url.hostname.toLowerCase())
      || !!url.port
      || !!url.username
      || !!url.password
      || !!url.search
      || !!url.hash
      || url.pathname.includes('%')
      || url.pathname.includes('\\')
      || url.pathname.includes('//')) {
    return undefined;
  }

  const segments = url.pathname.split('/').filter(Boolean);
  if (segments.length < 2
      || !HUGGING_FACE_REPOSITORY_ID.test(`${segments[0]}/${segments[1]}`)) {
    return undefined;
  }
  if (segments.length > 2) {
    const route = segments[2];
    const minimum = route === 'tree' ? 4 : 5;
    if (!HUGGING_FACE_ROUTES.has(route)
        || segments.length < minimum
        || segments.slice(3).some(segment => !segment || segment === '.' || segment === '..')) {
      return undefined;
    }
  }
  return `https://huggingface.co/${segments.join('/')}`;
}

/** @deprecated The staging input now also accepts canonical Hugging Face URLs. */
export function normalizeHuggingFaceRepositoryId(repository: unknown): string | undefined {
  return normalizeHuggingFaceReference(repository);
}

export function normalizePublicComponentUrl(value: unknown): string | undefined {
  const normalized = text(value);
  if (!normalized) {
    return undefined;
  }
  try {
    const url = new URL(normalized);
    if (url.protocol !== 'https:'
        || !url.hostname
        || !!url.username
        || !!url.password
        || !!url.search
        || !!url.hash
        || url.pathname.includes('%')
        || url.pathname.includes('\\')
        || url.pathname.includes('//')) {
      return undefined;
    }
    return url.toString();
  } catch {
    return undefined;
  }
}

export function parseModelStagingPrefill(
  fragment: string | null | undefined
): Record<string, unknown> | null {
  if (!fragment) {
    return null;
  }
  const value = fragment.startsWith('#') ? fragment.slice(1) : fragment;
  if (!value || /%(?![0-9A-Fa-f]{2})/.test(value)) {
    return null;
  }

  const params = new URLSearchParams(value);
  const allowed = new Set(['hf', ...COMPONENT_FRAGMENT_FIELDS.keys()]);
  for (const key of params.keys()) {
    if (!allowed.has(key) || params.getAll(key).length !== 1) {
      return null;
    }
  }

  const referenceValue = params.get('hf');
  const componentKeys = [...COMPONENT_FRAGMENT_FIELDS.keys()]
    .filter(key => params.has(key));
  if (params.has('configUrl') && params.has('modelConfigUrl')) {
    return null;
  }
  if (referenceValue !== null) {
    if (componentKeys.length > 0) {
      return null;
    }
    const reference = normalizeHuggingFaceReference(referenceValue);
    return reference ? {
      source: 'huggingface',
      repository: reference,
      outputFormat: 'model',
      modelType: 'llm_ggml',
      format: 'gguf'
    } : null;
  }
  if (componentKeys.length === 0) {
    return null;
  }

  const prefill: Record<string, unknown> = {
    source: HTTPS_COMPONENT_SOURCE,
    repository: '',
    outputFormat: 'model',
    modelType: 'llm_ggml'
  };
  for (const key of componentKeys) {
    const field = COMPONENT_FRAGMENT_FIELDS.get(key);
    const normalized = normalizePublicComponentUrl(params.get(key));
    if (!field || !normalized || (prefill[field] && prefill[field] !== normalized)) {
      return null;
    }
    prefill[field] = normalized;
  }
  if (missingRemoteTextBundle(prefill).length > 0) {
    return null;
  }

  const modelUrl = prefill['modelUrl'] as string;
  const modelPath = new URL(modelUrl).pathname.toLowerCase();
  if (!modelPath.endsWith('.gguf') && !modelPath.endsWith('.ggml')) {
    return null;
  }
  prefill['format'] = modelPath.endsWith('.ggml') ? 'ggml' : 'gguf';
  prefill['modelId'] = modelIdFromUrl(modelUrl);
  return prefill;
}

/**
 * Browsers return no File when a chooser is cancelled. Preserve the prior selection.
 */
export function retainSelectionOnPickerCancel(
  current: File | undefined,
  selected: File | undefined
): File | undefined {
  return selected ?? current;
}

/**
 * Persist only non-secret, serializable form data. Browser File handles and HF tokens
 * deliberately never enter localStorage.
 */
export function serializeImportDraft(value: Record<string, unknown>): string {
  const fields: Record<string, unknown> = {};
  const source = value['source'];
  for (const key of DRAFT_FIELDS) {
    const fieldValue = value[key];
    if (key === 'repository') {
      const repository = source === 'huggingface'
        ? normalizeHuggingFaceReference(fieldValue)
        : undefined;
      if (repository) {
        fields[key] = repository;
      }
    } else if (typeof fieldValue === 'string'
        || typeof fieldValue === 'boolean'
        || typeof fieldValue === 'number') {
      fields[key] = fieldValue;
    }
  }
  return JSON.stringify({ version: DRAFT_VERSION, fields });
}

export function restoreImportDraft(serialized: string | null): Record<string, unknown> | null {
  if (!serialized) {
    return null;
  }
  try {
    const parsed = JSON.parse(serialized) as {
      version?: unknown;
      fields?: unknown;
    };
    if (parsed.version !== DRAFT_VERSION
        || parsed.fields === null
        || typeof parsed.fields !== 'object'
        || Array.isArray(parsed.fields)) {
      return null;
    }
    const restored: Record<string, unknown> = {};
    const serializedFields = parsed.fields as Record<string, unknown>;
    const source = serializedFields['source'];
    for (const key of DRAFT_FIELDS) {
      const fieldValue = serializedFields[key];
      if (key === 'repository') {
        const repository = source === 'huggingface'
          ? normalizeHuggingFaceReference(fieldValue)
          : undefined;
        if (repository) {
          restored[key] = repository;
        }
      } else if (typeof fieldValue === 'string'
          || typeof fieldValue === 'boolean'
          || typeof fieldValue === 'number') {
        restored[key] = fieldValue;
      }
    }
    return restored;
  } catch {
    return null;
  }
}

function compactAssets(assets: TextModelAssetMap): TextModelAssetMap {
  return Object.fromEntries(
    Object.entries(assets).filter(([, value]) => typeof value === 'string' && value.length > 0)
  ) as TextModelAssetMap;
}

function text(value: unknown): string | undefined {
  if (typeof value !== 'string' || value.trim().length === 0) {
    return undefined;
  }
  return value.trim();
}

function usable(file: File | undefined): boolean {
  return !!file && file.size > 0;
}

function modelIdFromUrl(value: string): string {
  const fileName = new URL(value).pathname.split('/').filter(Boolean).pop() || 'mobile-chat';
  const stem = fileName.replace(/\.(gguf|ggml)$/i, '');
  const normalized = stem.replace(/[^A-Za-z0-9_-]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .toLowerCase();
  return normalized || 'mobile-chat';
}
