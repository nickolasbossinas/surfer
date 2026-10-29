import { describe, expect, it } from 'vitest';
import {
  decodeFrame,
  encodeFrame,
  FRAME_FLAG_KEYFRAME,
  FRAME_FLAG_REFINE,
  FRAME_FLAG_SCROLL_TARGET,
  FRAME_HEADER_SIZE,
  ImageFormat,
  parseClientMessage,
  RECT_HEADER_SIZE,
} from '../src/protocol.js';

describe('frame encoding', () => {
  it('round-trips an update with rectangles and a scroll target', () => {
    const buf = encodeFrame(
      {
        flags: FRAME_FLAG_REFINE,
        frameId: 4_000_000_000,
        tabId: 7,
        width: 1280,
        height: 800,
        pageScale: 1.5,
        scrollX: 0,
        scrollY: 1234.5,
        offsetTop: 0,
        scrollTarget: { id: 9, rect: { x: 0, y: 56, w: 640, h: 744 }, scrollLeft: 0, scrollTop: 321.25 },
      },
      [
        { x: 0, y: 0, w: 32, h: 64, format: ImageFormat.Png, data: Buffer.from([1, 2, 3]) },
        { x: 640, y: 96, w: 1, h: 1, format: ImageFormat.Jpeg, data: Buffer.from([4, 5]) },
      ],
    );
    expect(buf.length).toBe(FRAME_HEADER_SIZE + 2 * RECT_HEADER_SIZE + 5);
    const { header, rects } = decodeFrame(buf);
    expect(header.flags).toBe(FRAME_FLAG_REFINE | FRAME_FLAG_SCROLL_TARGET);
    expect(header.frameId).toBe(4_000_000_000);
    expect(header.width).toBe(1280);
    expect(header.scrollY).toBeCloseTo(1234.5);
    expect(header.scrollTarget).toEqual({ id: 9, rect: { x: 0, y: 56, w: 640, h: 744 }, scrollLeft: 0, scrollTop: 321.25 });
    expect(rects.map((r) => ({ ...r, data: [...r.data] }))).toEqual([
      { x: 0, y: 0, w: 32, h: 64, format: ImageFormat.Png, data: [1, 2, 3] },
      { x: 640, y: 96, w: 1, h: 1, format: ImageFormat.Jpeg, data: [4, 5] },
    ]);
  });

  it('clears the scroll-target flag when there is no target', () => {
    const buf = encodeFrame(
      { flags: FRAME_FLAG_KEYFRAME | FRAME_FLAG_SCROLL_TARGET, frameId: 1, tabId: 1, width: 10, height: 10, pageScale: 1, scrollX: 0, scrollY: 0, offsetTop: 0 },
      [],
    );
    expect(decodeFrame(buf).header.scrollTarget).toBeUndefined();
    expect(decodeFrame(buf).header.flags).toBe(FRAME_FLAG_KEYFRAME);
  });
});

describe('parseClientMessage', () => {
  it('accepts valid messages', () => {
    expect(parseClientMessage('{"t":"viewport","w":800,"h":1200,"dpr":1.5}')).toEqual({ t: 'viewport', w: 800, h: 1200, dpr: 1.5 });
    expect(parseClientMessage('{"t":"tabNew"}')).toEqual({ t: 'tabNew' });
    expect(parseClientMessage('{"t":"hello","proto":1,"app":"x","appVersion":"1","deviceId":"abcdefghijklmnopqrstuvwxyz012345"}')).not.toBeNull();
    expect(
      parseClientMessage('{"t":"touch","phase":"move","time":1,"points":[{"id":0,"x":1,"y":2},{"id":1,"x":3,"y":4,"force":0.5}]}'),
    ).not.toBeNull();
  });

  it('rejects malformed input', () => {
    for (const raw of [
      'not json',
      '[]',
      '{"t":"nope"}',
      '{"t":"viewport","w":"800","h":1,"dpr":1}',
      '{"t":"viewport","w":800,"h":1}',
      '{"t":"viewport","w":NaN,"h":1,"dpr":1}',
      '{"t":"touch","phase":"wiggle","time":1,"points":[]}',
      '{"t":"touch","phase":"move","time":1,"points":[{"id":0,"x":"1","y":2}]}',
      '{"t":"pickerReply","pickerId":1,"selected":["a"]}',
      '{"t":"__proto__"}',
      '{"t":"toString"}',
      '{"t":"hello","proto":1,"app":"x","appVersion":"1","deviceId":"short"}',
      '{"t":"hello","proto":1,"app":"x","appVersion":"1","deviceId":"has spaces and is long enough"}',
      '{"t":"restoreLocal","key":"https://a/","items":{"k":1}}',
    ]) {
      expect(parseClientMessage(raw), raw).toBeNull();
    }
  });

  it('rejects too many touch points', () => {
    const points = Array.from({ length: 11 }, (_, id) => ({ id, x: 0, y: 0 }));
    expect(parseClientMessage(JSON.stringify({ t: 'touch', phase: 'move', time: 1, points }))).toBeNull();
  });
});
