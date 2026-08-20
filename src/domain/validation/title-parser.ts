export interface ParsedFeatureEpicTitle {
  kind: "feature";
  domainCode: string;
  summary: string;
}

export interface ParsedSupportEpicTitle {
  kind: "support";
  summary: string;
}

export type ParsedEpicTitle = ParsedFeatureEpicTitle | ParsedSupportEpicTitle;

export interface ParsedStoryTitle {
  domainCode: string;
  sequence: string;
  summary: string;
}

export interface ParsedFeatureTaskTitle {
  kind: "feature";
  domainCode: string;
  sequence: string;
  area: string;
  summary: string;
}

export interface ParsedCommonTaskTitle {
  kind: "common";
  category: string;
  area?: string;
  summary: string;
}

export type ParsedTaskTitle = ParsedFeatureTaskTitle | ParsedCommonTaskTitle;

export function parseEpicTitle(title: string): ParsedEpicTitle | undefined {
  const featureMatch = /^\[([A-Z][A-Z0-9]*)\] (.+)$/.exec(title);
  if (featureMatch) {
    return { kind: "feature", domainCode: featureMatch[1], summary: featureMatch[2] };
  }

  if (/^[^\[\]].+$/.test(title)) {
    return { kind: "support", summary: title };
  }

  return undefined;
}

export function parseStoryTitle(title: string): ParsedStoryTitle | undefined {
  const match = /^\[([A-Z][A-Z0-9]*)-(\d+)\] (.+)$/.exec(title);

  return match
    ? { domainCode: match[1], sequence: match[2], summary: match[3] }
    : undefined;
}

export function parseTaskTitle(title: string): ParsedTaskTitle | undefined {
  const featureMatch = /^\[([A-Z][A-Z0-9]*)-(\d+) \| ([^\]]+)\] (.+)$/.exec(title);
  if (featureMatch) {
    return {
      kind: "feature",
      domainCode: featureMatch[1],
      sequence: featureMatch[2],
      area: featureMatch[3],
      summary: featureMatch[4],
    };
  }

  const commonMatch = /^\[([A-Z]+)(?: \| ([^\]]+))?\] (.+)$/.exec(title);
  if (commonMatch) {
    return {
      kind: "common",
      category: commonMatch[1],
      area: commonMatch[2],
      summary: commonMatch[3],
    };
  }

  return undefined;
}
