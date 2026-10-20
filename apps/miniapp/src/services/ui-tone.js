/** 徽标色调 → design-system 的 ux-badge-* 后缀。
 * 各业务域的展示映射都从这里取，避免每个域各写一份 class 名（写错不会报错，只会静默丢样式）。 */
export const TONE_CLASS = { ok: 'ux-badge-ok', warn: 'ux-badge-warn', risk: 'ux-badge-risk', muted: 'ux-badge-muted' }
export const toneClass = tone => TONE_CLASS[tone] || TONE_CLASS.muted
