// 从独立项目 widget-spacing 原样移植：只做纯文本计算，不依赖 React / DOM。
const NUMBER = '-?(?:\\d+\\.?\\d*|\\.\\d+)';
const UNIT = 'px|rpx|em|rem|%|vh|vw';

const LEFT_PATTERN = new RegExp(
  `(\\bleft\\b\\s*[:=]\\s*)(['"]?)(${NUMBER})(${UNIT})?\\2`,
  'i',
);
const WIDTH_PATTERN = new RegExp(
  `(\\bwidth\\b\\s*[:=]\\s*)(['"]?)(${NUMBER})(${UNIT})?\\2`,
  'i',
);

function round(value) {
  return Math.round(value * 1000) / 1000;
}

function formatNumber(value) {
  return String(round(value));
}

function replaceLeftValue(line, newLeft) {
  return line.replace(LEFT_PATTERN, (_match, prefix, quote, _number, unit) => {
    return `${prefix}${quote}${formatNumber(newLeft)}${unit ?? ''}${quote}`;
  });
}

/**
 * 从上往下处理每行的 XML widget：
 * 第一个 widget 保留原 left，后续 left = 前一个 left + 前一个 width + 间隔。
 */
export function computeLayout(text, gap) {
  const gapValue = Number.isFinite(Number(gap)) ? Number(gap) : 0;
  const lines = String(text ?? '').split('\n');
  const widgets = [];
  let previous = null;

  const outputLines = lines.map((line, index) => {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('<!--')) return line;

    const leftMatch = LEFT_PATTERN.exec(line);
    const widthMatch = WIDTH_PATTERN.exec(line);
    if (!leftMatch || !widthMatch) return line;

    const originalLeft = Number(leftMatch[3]);
    const width = Number(widthMatch[3]);
    if (!Number.isFinite(originalLeft) || !Number.isFinite(width)) return line;

    const newLeft = previous
      ? round(previous.left + previous.width + gapValue)
      : originalLeft;

    widgets.push({
      line: index + 1,
      originalLeft,
      width,
      newLeft,
      delta: round(newLeft - originalLeft),
    });

    previous = { left: newLeft, width };
    return newLeft === originalLeft ? line : replaceLeftValue(line, newLeft);
  });

  return {
    text: outputLines.join('\n'),
    widgets,
  };
}
