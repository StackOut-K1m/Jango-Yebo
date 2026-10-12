import { useEffect, useRef, useState } from 'react';
import type { PointerEvent, ReactNode } from 'react';
import { animate, motion, useMotionValue, useReducedMotion, useTransform } from 'motion/react';
import type { MotionStyle } from 'motion/react';

/** 접었을 때 남겨 두는 폭. 지갑이 이만큼 삐져나와 잡을 곳이 된다(CSS 의 --wallet-peek 과 같아야 한다). */
const PEEK = 56;

/** 삐져나온 지갑을 잡아당기면 손을 따라 나오고, 놓으면 그 속도를 이어받아 미끄러진다.
 *
 * ## 움직이는 건 그리드 트랙 하나다
 *
 * 지갑 컬럼은 페이지 오른쪽 끝에 붙어 있다. 그래서 **트랙이 줄어드는 것 자체가**
 * 화면에서는 지갑이 오른쪽으로 밀려나가는 움직임이고, 동시에 왼쪽 유리창이 그만큼
 * 넓어진다 — 끄는 내내 둘이 실시간으로 맞물린다. 서랍을 따로 밀면 상쇄돼서 사라진다.
 *
 * 트랙이 매 프레임 바뀌는 비용은 BalanceChart 쪽에서 막는다 — 그쪽 ResizeObserver 가
 * 폭을 바로 반영하지 않고 멎은 뒤 한 번만 잡고, 그 사이 높이는 CSS 가 고정해 둔다. */
export function WalletWorkspace({ children, wallet }: { children: ReactNode; wallet: ReactNode }) {
  const workspace = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(true);
  const [dragging, setDragging] = useState(false);
  // 지갑이 실제로 움직이는 중인가. 유리창 안의 요소들이 서로 다른 속도로 흩어지는 구간이다.
  const [moving, setMoving] = useState(false);
  const [width, setWidth] = useState(360);
  const reveal = useMotionValue(1);
  // 힌트가 마우스를 따라가는 높이. 모션 값이라 포인터가 움직여도 리렌더가 없다.
  const hintY = useMotionValue(60);
  const hintTop = useTransform(hintY, (value) => `${value}px`);
  const reducedMotion = useReducedMotion();
  const drag = useRef<{ id: number; x: number; start: number; moved: boolean } | null>(null);
  const suppressClick = useRef(false);
  // 손을 뗀 순간의 속도. 스프링이 이걸 이어받아야 던진 대로 간다.
  const flick = useRef(0);
  // 이번 애니메이션이 「드래그를 놓아서」 시작됐나. 그 경우엔 흐리지 않는다.
  const fromDrag = useRef(false);
  // 첫 렌더에서 흐려지는 걸 막는다 — 마운트 때도 애니메이션 효과가 한 번 돈다.
  const mounted = useRef(false);

  useEffect(() => {
    const element = workspace.current;
    if (!element) return;
    const observer = new ResizeObserver(([entry]) => {
      setWidth(Math.min(430, Math.max(240, entry.contentRect.width * .31)));
      // 페이지가 가운데 정렬이라 지갑 오른쪽에 여백이 남는다. 접혔을 때 그만큼 더 밀어
      // 화면 가장자리에 붙여야 「구석에서 튀어나온」 것으로 읽힌다. CSS 로는 못 재는 값이다.
      const right = element.getBoundingClientRect().right;
      element.style.setProperty('--page-edge', `${Math.max(0, Math.round(document.documentElement.clientWidth - right))}px`);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (dragging) return;
    let cancelled = false;
    // 손으로 끌었으면 놓는 순간 이미 풀렸다 — 그 뒤 스프링 구간은 흐리지 않는다.
    // 클릭·키보드로 여닫을 땐 「놓는 순간」이 없으므로 도는 동안 흐린다.
    if (fromDrag.current) fromDrag.current = false;
    else if (mounted.current) setMoving(true);
    mounted.current = true;
    const animation = animate(reveal, open ? 1 : 0, reducedMotion
      ? { duration: 0 }
      // damping 을 내리면 끝에서 통 하고 반동이 커지고, 올리면 묵직하게 눌러앉는다.
      : { type: 'spring', stiffness: 420, damping: 38, velocity: flick.current, restDelta: .001 });
    flick.current = 0;
    void animation.then(() => { if (!cancelled) setMoving(false); });
    return () => { cancelled = true; animation.stop(); };
  }, [open, dragging, reveal, reducedMotion]);


  function start(event: PointerEvent<HTMLButtonElement>) {
    if (!event.isPrimary || event.button !== 0) return;
    reveal.stop();
    suppressClick.current = false;
    drag.current = { id: event.pointerId, x: event.clientX, start: reveal.get(), moved: false };
    event.currentTarget.setPointerCapture(event.pointerId);
    setDragging(true);
  }

  function move(event: PointerEvent<HTMLButtonElement>) {
    // 잡고 있지 않을 때도 온다 — 힌트를 포인터 높이에 붙인다.
    hintY.set(event.clientY - event.currentTarget.getBoundingClientRect().top);
    const current = drag.current;
    if (!current || current.id !== event.pointerId) return;
    const distance = current.x - event.clientX;
    // 한 번 움직였으면 놓을 때까지 걸어 둔다(래치). 「지금 움직이는 중」으로 판정하면
    // 쥔 채 손을 멈춘 순간 풀려서 깜빡인다.
    if (!current.moved && Math.abs(distance) > 5) {
      current.moved = true;
      setMoving(true);
    }
    // 실제로 움직이는 거리는 삐져나온 몫을 뺀 만큼이다.
    const raw = current.start + distance / Math.max(1, width - PEEK);
    if (current.moved) reveal.set(Math.max(0, Math.min(1, raw)));
  }

  function finish(event: PointerEvent<HTMLButtonElement>, cancelled = false) {
    const current = drag.current;
    if (!current || current.id !== event.pointerId) return;
    drag.current = null;
    suppressClick.current = current.moved;
    fromDrag.current = current.moved;
    setMoving(false);
    // 상태를 바꾸기 전에 읽는다. 렌더가 한 번 지나가면 속도가 0 으로 식는다.
    const velocity = reveal.getVelocity();
    if (!cancelled && current.moved) {
      flick.current = velocity;
      // 짧고 빠르게 튕긴 것도 살린다. 이동량만 보면 20px 튕김이 「안 움직임」으로 읽힌다.
      setOpen(Math.abs(velocity) > .8 ? velocity > 0 : reveal.get() >= .5);
    }
    setDragging(false);
  }

  return (
    <motion.div ref={workspace} className="workspace wallet-workspace" data-wallet-open={open} data-dragging={dragging}
      data-moving={moving}
      style={{ '--wallet-reveal': reveal, '--wallet-width': `${width}px` } as MotionStyle}>
      {children}
      <div className="wallet-dock">
        {/* 넘쳐 나간 서랍만 자른다. 손잡이와 힌트는 이 밖에 있어야 잘리지 않는다. */}
        <div className="wallet-clip">
          <div className="wallet-drawer" id="dashboard-wallet" inert={!open || dragging} aria-hidden={!open || dragging}>
            {wallet}
          </div>
        </div>
        {/* 상자를 없애고 삐져나온 지갑 자체를 잡게 했다. 보이진 않지만 버튼은 남는다 —
            없애면 키보드로 지갑을 여닫을 방법이 사라진다. */}
        <button type="button" className="wallet-grab" aria-label={open ? '지갑 접기' : '지갑 펼치기'}
          aria-expanded={open} aria-controls="dashboard-wallet" title="클릭하거나 좌우로 당겨 지갑 여닫기"
          onPointerDown={start} onPointerMove={move} onPointerUp={(event) => finish(event)}
          onPointerCancel={(event) => finish(event, true)} onLostPointerCapture={(event) => finish(event, true)}
          onClick={(event) => { if (!suppressClick.current || event.detail === 0) setOpen((value) => !value); suppressClick.current = false; }}
          onKeyDown={(event) => {
            if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
              event.preventDefault(); setOpen(event.key === 'ArrowLeft');
            }
          }}>
          <motion.span className="wallet-grab-hint" aria-hidden="true" style={{ '--hint-y': hintTop } as MotionStyle}>
            {open ? '옆으로 밀어보세요' : '옆으로 당겨보세요'}
          </motion.span>
        </button>
      </div>
    </motion.div>
  );
}
