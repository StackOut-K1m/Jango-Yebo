import { useEffect } from 'react';
import { animate, motion, useMotionValue, useReducedMotion, useTransform } from 'motion/react';

/**
 * 숫자가 0에서 굴러올라 제 값에 멈춘다. 첫 렌더와 예보 새로고침에서 쓴다.
 *
 * 값은 모션 값으로 흐르고 문자열은 `useTransform` 이 만든다 — 프레임마다 React 가
 * 다시 그리지 않는다. 다시 굴리고 싶으면 바깥에서 `key` 를 바꿔 새로 마운트한다.
 *
 * 범위(`120~140만`)도 한 문자열이라 값을 둘까지 받는다. 두 끝이 따로 놀면
 * 중간 프레임에서 min 이 max 를 넘어 「140~120」 같은 글자가 스친다.
 */
export function Rolling({ values, format }: { values: number[]; format: (v: number[]) => string }) {
  const first = useMotionValue(0);
  const second = useMotionValue(0);
  const reducedMotion = useReducedMotion();
  const text = useTransform([first, second], (live: number[]) =>
    format(live.slice(0, values.length).map(Math.round)),
  );

  useEffect(() => {
    // easeOutCubic. expo 계열([.16,1,.3,1])은 꼬리가 너무 길어, 정수로 반올림해 찍으면
    // 마지막 구간에서 값이 몇 단위씩만 움직인다 — 멈춘 듯하다 툭 바뀌며 끝나 멈칫거려 보인다.
    const options = reducedMotion ? { duration: 0 } : { duration: .75, ease: [.33, 1, .68, 1] as const };
    const runs = [animate(first, values[0] ?? 0, options)];
    if (values.length > 1) runs.push(animate(second, values[1] ?? 0, options));
    return () => runs.forEach((run) => run.stop());
    // values 는 매 렌더 새 배열이라 원소로 비교한다.
  }, [values[0], values[1], values.length, first, second, reducedMotion]);

  return <motion.span>{text}</motion.span>;
}
