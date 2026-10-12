import type { Weather } from './Weather';

interface Drop {
  x: number;
  y: number;
  r: number;
  stretch: number;
  seed: number;
}
interface TrailPoint {
  x: number;
  y: number;
  time: number;
  r: number;
}
interface Run extends Drop {
  trail: TrailPoint[];
  anchor: number;
  velocity: number;
  hold: number;
  burst: number;
}

/** Approved preview: rain 25%, condensation 65%, sunlight 80%. Content stays above the glass. */
export interface WindowWeather {
  /** 날씨만 갈아끼운다. 컨텍스트·셰이더·필름은 그대로 둔다. */
  setWeather(next: Weather): void;
  destroy(): void;
}

export function mountWindowWeather(host: HTMLDivElement, weather: Weather): WindowWeather {
  function layer(name: string) {
    const element = document.createElement('canvas');
    element.className = 'glass-weather__' + name;
    host.append(element);
    return element;
  }
  host.replaceChildren();
  const sunlight = layer('sunlight');
  const condensation = layer('condensation');
  const rivulets = layer('rivulets');
  const canvas = layer('water');
  const sunCtx = sunlight.getContext('2d')!;
  const wet = condensation.getContext('2d')!;
  const ctx = rivulets.getContext('2d')!;
  const film = document.createElement('canvas'),
    optics = document.createElement('canvas');
  const filmCtx = film.getContext('2d')!,
    opticsCtx = optics.getContext('2d')!;
  // 유리 바탕색은 CSS 토큰을 따른다. 물방울·햇빛 색은 빛의 색이라 테마와 무관하게 고정이다.
  const glass = getComputedStyle(host).getPropertyValue('--glass').trim();
  // 밝은 유리에서는 습기 막의 작은 점이 먼지로 읽힌다. 물방울과 습기만 남긴다.
  const specks = document.documentElement.dataset.theme !== 'light';
  // 날씨는 바뀐다. 상수로 닫아 두면 갈아끼울 때마다 모듈을 통째로 다시 만들어야 한다.
  let current = weather;
  let sunStrength = 0,
    moisture = 0,
    animated = false;
  function applyWeather() {
    sunStrength = current === 'sunny' ? 0.8 : 0;
    moisture = current === 'sunny' ? 0 : 0.65;
    animated = current !== 'cloudy';
    sunlight.style.opacity = String(sunStrength);
    condensation.style.opacity = String(moisture);
  }
  applyWeather();
  let width = 0,
    height = 0,
    elapsed = 0;
  let drops: Drop[] = [],
    runs: Run[] = [];
  // 리사이즈를 늦추는 타이머. 까닭은 아래 ResizeObserver 주석.
  let resizeTimer = 0;
  // 필름을 이미 만들었나. 한 번만 만들고 이후에는 늘려 쓴다(makeFilm 주석).
  let filmMade = false;
  // 물 셰이더는 비가 처음 올 때 한 번만 만든다. 컴파일이 비싸다.
  let waterReady = false;
  let drawLenses: (moisture: number) => void = () => {};
  // 비를 벗어날 때 물방울을 지운다. preserveDrawingBuffer 라 안 지우면 마지막 프레임이 남는다.
  let clearWater = () => {};
  let releaseWater = () => {};
  const random = (min: number, max: number) => min + Math.random() * (max - min);
  function initWater() {
    const context = canvas.getContext('webgl', {
      alpha: true,
      premultipliedAlpha: false,
      preserveDrawingBuffer: true,
    });
    if (!context) return;
    const gl: WebGLRenderingContext = context;
    function shader(type: number, source: string) {
      const result = gl.createShader(type)!;
      gl.shaderSource(result, source);
      gl.compileShader(result);
      if (!gl.getShaderParameter(result, gl.COMPILE_STATUS))
        throw new Error(gl.getShaderInfoLog(result) ?? 'Water shader compilation failed');
      return result;
    }
    const program = gl.createProgram()!;
    gl.attachShader(
      program,
      shader(
        gl.VERTEX_SHADER,
        `
    attribute vec2 aCenter; attribute vec2 aRadius; attribute vec2 aLocal; attribute float aSeed;
    uniform vec2 uSize;
    varying vec2 vLocal; varying vec2 vCenter; varying vec2 vRadius; varying float vSeed;
    void main() {
      vLocal=aLocal; vCenter=aCenter; vRadius=aRadius; vSeed=aSeed;
      vec2 p=(aCenter+aLocal*aRadius)/uSize;
      gl_Position=vec4(p.x*2.0-1.0,1.0-p.y*2.0,0.0,1.0);
    }
  `,
      ),
    );
    gl.attachShader(
      program,
      shader(
        gl.FRAGMENT_SHADER,
        `
    precision highp float;
    uniform sampler2D uScene; uniform vec2 uSize; uniform float uLight;
    varying vec2 vLocal; varying vec2 vCenter; varying vec2 vRadius; varying float vSeed;
    void main() {
      vec2 p=vLocal;
      float width=.91+.055*p.y+.028*sin(p.y*4.0+vSeed);
      vec2 q=vec2(p.x/width,p.y);
      float rr=dot(q,q);
      float aa=1.35/min(vRadius.x,vRadius.y);
      float cover=1.0-smoothstep(1.0-aa,1.0,rr);
      if(cover<=0.0) discard;
      float z=sqrt(max(.008,1.0-rr));
      vec3 normal=normalize(vec3(q.x,q.y*.88,z));
      vec3 ray=refract(vec3(0.,0.,-1.),normal,1.0/1.333);
      vec2 screen=vCenter+p*vRadius;
      vec2 offset=ray.xy/max(.25,-ray.z)*vRadius*5.0;
      vec3 refracted=texture2D(uScene,clamp((screen+offset)/uSize,vec2(.001),vec2(.999))).rgb;
      float fresnel=.0204+.9796*pow(1.0-normal.z,5.0);
      vec3 reflectedDirection=reflect(vec3(0.,0.,-1.),normal);
      vec3 key=normalize(vec3(-.55,-.7,.72));
      float spec=pow(max(0.0,dot(reflectedDirection,key)),40.0);
      float soft=pow(max(0.0,dot(reflectedDirection,key)),12.0);
      float caustic=pow(max(0.0,dot(normal,normalize(vec3(.22,.81,.15)))),18.0);
      vec3 reflection=mix(vec3(.07,.11,.14),vec3(.28,.34,.37),max(0.0,-normal.y));
      vec3 color=mix(refracted,reflection,fresnel*.68);
      color*=1.0-.32*smoothstep(.64,.99,rr)*max(0.0,normal.x*.65+normal.y*.45);
      float rim=smoothstep(.48,.80,rr)*(1.0-smoothstep(.9,1.0,rr));
      float facing=pow(max(0.0,-normal.x*.7-normal.y*.7),4.0);
      color+=vec3(.9,.95,1.0)*(spec*.98+soft*.045+rim*facing*.42)*(0.7+uLight*.3);
      color+=mix(vec3(.65,.79,.86),vec3(.91,.87,.68),uLight)*caustic*.32;
      gl_FragColor=vec4(color,cover);
    }
  `,
      ),
    );
    gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS))
      throw new Error(gl.getProgramInfoLog(program) ?? 'Water shader link failed');
    gl.useProgram(program);
    const vertices = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, vertices);
    for (const [name, size, offset] of [
      ['aCenter', 2, 0],
      ['aRadius', 2, 8],
      ['aLocal', 2, 16],
      ['aSeed', 1, 24],
    ] as const) {
      const location = gl.getAttribLocation(program, name);
      gl.enableVertexAttribArray(location);
      gl.vertexAttribPointer(location, size, gl.FLOAT, false, 28, offset);
    }
    const sceneTexture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, sceneTexture);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.uniform1i(gl.getUniformLocation(program, 'uScene'), 0);
    const sizeUniform = gl.getUniformLocation(program, 'uSize');
    const lightUniform = gl.getUniformLocation(program, 'uLight');
    let vertexData = new Float32Array(0);
    const quad = [-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1];

    function renderLenses(moisture: number) {
      opticsCtx.fillStyle = glass;
      opticsCtx.fillRect(0, 0, width, height);
      const strength = sunStrength;
      opticsCtx.globalAlpha = strength;
      opticsCtx.drawImage(sunlight, 0, 0);
      opticsCtx.globalAlpha = moisture;
      opticsCtx.drawImage(condensation, 0, 0);
      opticsCtx.globalAlpha = 1;
      opticsCtx.drawImage(rivulets, 0, 0, width, height);
      const all = [...drops, ...runs];
      const length = all.length * 42;
      if (vertexData.length !== length) vertexData = new Float32Array(length);
      let index = 0;
      for (const d of all) {
        for (let i = 0; i < 12; i += 2) {
          vertexData[index++] = d.x;
          vertexData[index++] = d.y;
          vertexData[index++] = d.r;
          vertexData[index++] = d.r * d.stretch;
          vertexData[index++] = quad[i];
          vertexData[index++] = quad[i + 1];
          vertexData[index++] = d.seed;
        }
      }
      if (gl.isContextLost()) return;
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.uniform2f(sizeUniform, width, height);
      gl.uniform1f(lightUniform, strength);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, optics);
      gl.bufferData(gl.ARRAY_BUFFER, vertexData, gl.DYNAMIC_DRAW);
      gl.drawArrays(gl.TRIANGLES, 0, all.length * 6);
    }

    drawLenses = renderLenses;
    clearWater = () => {
      if (gl.isContextLost()) return;
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
    };
    releaseWater = () => {
      for (const item of gl.getAttachedShaders(program) ?? []) gl.deleteShader(item);
      gl.deleteBuffer(vertices);
      gl.deleteTexture(sceneTexture);
      gl.deleteProgram(program);
      gl.getExtension('WEBGL_lose_context')?.loseContext();
    };
  }
  function drawSun() {
    sunCtx.clearRect(0, 0, width, height);
    const bloom = sunCtx.createRadialGradient(
      width * 0.87,
      -height * 0.07,
      0,
      width * 0.87,
      -height * 0.07,
      width * 0.85,
    );
    bloom.addColorStop(0, '#fff5dcb0');
    bloom.addColorStop(0.24, '#ffe5b92e');
    bloom.addColorStop(0.6, '#fcd99a09');
    bloom.addColorStop(1, '#fcd99a00');
    sunCtx.fillStyle = bloom;
    sunCtx.fillRect(0, 0, width, height);
    sunCtx.save();
    sunCtx.translate(width * 0.85, -height * 0.1);
    sunCtx.rotate(Math.sin(elapsed * 0.08) * 0.018);
    sunCtx.filter = 'blur(16px)';
    for (const [angle, spread, alpha] of [
      [0.18, 0.055, 0.07],
      [0.43, 0.065, 0.055],
      [0.7, 0.085, 0.08],
      [0.94, 0.05, 0.035],
    ]) {
      const length = Math.hypot(width, height) * 1.3;
      const fade = sunCtx.createLinearGradient(
        0,
        0,
        -Math.sin(angle) * length,
        Math.cos(angle) * length,
      );
      fade.addColorStop(0, `rgba(255,237,199,${alpha})`);
      fade.addColorStop(1, '#ffedc700');
      sunCtx.beginPath();
      sunCtx.moveTo(0, 0);
      sunCtx.lineTo(-Math.sin(angle - spread) * length, Math.cos(angle - spread) * length);
      sunCtx.lineTo(-Math.sin(angle + spread) * length, Math.cos(angle + spread) * length);
      sunCtx.fillStyle = fade;
      sunCtx.fill();
    }
    sunCtx.restore();
  }

  // A fixed smooth noise field keeps condensation patches stable while adjusting humidity.
  function noise(x: number, y: number) {
    const ix = Math.floor(x),
      iy = Math.floor(y);
    const hash = (a: number, b: number) => {
      const v = Math.sin(a * 127.1 + b * 311.7) * 43758.5453;
      return v - Math.floor(v);
    };
    const sx = (x - ix) ** 2 * (3 - 2 * (x - ix));
    const sy = (y - iy) ** 2 * (3 - 2 * (y - iy));
    const a = hash(ix, iy) * (1 - sx) + hash(ix + 1, iy) * sx;
    const b = hash(ix, iy + 1) * (1 - sx) + hash(ix + 1, iy + 1) * sx;
    return a * (1 - sy) + b * sy;
  }

  /**
   * 노이즈 질감이라 **크기가 바뀌었다고 다시 만들 이유가 없다** — 늘려 그리면 된다.
   *
   * 판 전체를 픽셀 하나씩 도는 루프다(950×920 이면 87만 픽셀 × noise 2회, 거기에
   * ellipse 약 8,700개). 지갑을 여닫을 때마다 이게 다시 돌아서, 반복하면 그때마다 멎었다.
   * 처음 한 번만 만들고 paint() 가 현재 크기로 늘려 그린다. 노이즈라 배율은 안 보인다.
   */
  function makeFilm() {
    if (filmMade) return;
    filmMade = true;
    film.width = width;
    film.height = height;
    const grain = filmCtx.createImageData(width, height);
    for (let y = 0; y < height; y++)
      for (let x = 0; x < width; x++) {
        const index = (y * width + x) * 4;
        const patch = noise(x / 155, y / 170) * 0.65 + noise(x / 42, y / 57) * 0.35;
        grain.data[index] = 174;
        grain.data[index + 1] = 191;
        grain.data[index + 2] = 186;
        grain.data[index + 3] = 7 + patch ** 1.8 * 64 + Math.random() * 12;
      }
    filmCtx.putImageData(grain, 0, 0);
    // Dense, tiny menisci catch light instead of laying a white sheet over the glass.
    for (let i = 0; specks && i < (width * height) / 100; i++) {
      const x = random(0, width),
        y = random(0, height);
      if (noise(x / 130, y / 170) < random(0.08, 0.7)) continue;
      const r = random(0.25, 1.25);
      filmCtx.fillStyle = '#06131335';
      filmCtx.beginPath();
      filmCtx.ellipse(x + 0.4, y + 0.7, r, r * 1.25, 0, 0, Math.PI * 2);
      filmCtx.fill();
      filmCtx.fillStyle = `rgba(226,236,211,${random(0.12, 0.46)})`;
      filmCtx.beginPath();
      filmCtx.ellipse(x - r * 0.3, y - r * 0.4, r * 0.45, r * 0.65, 0.4, 0, Math.PI * 2);
      filmCtx.fill();
    }
  }

  function populate() {
    const count = Math.round((((width * height) / 1250) * 25) / 100);
    drops = Array.from({ length: count }, (_, i) => ({
      x: random(0, width),
      y: random(0, height),
      r: i % 20 === 0 ? random(6, 10) : i % 20 < 7 ? random(2.5, 5) : random(0.7, 2.4),
      stretch: random(0.93, 1.22),
      seed: random(0, 30),
    }));
    const runCount = Math.max(2, Math.round(((width / 280) * 25) / 65));
    runs = Array.from({ length: runCount }, (_, i) => {
      const x = width * ((i + random(0.3, 0.7)) / runCount),
        y = random(height * 0.23, height * 0.65),
        seed = random(1, 500),
        r = random(7, 10);
      const trail: TrailPoint[] = [];
      for (let py = Math.max(-5, y - random(75, 200)); py < y; py += 3)
        trail.push({
          x: x + (noise(seed, py / 65) - noise(seed, y / 65)) * 5,
          y: py,
          time: elapsed - (y - py) / 22,
          r: r * 0.38,
        });
      return {
        x,
        y,
        r,
        seed,
        trail,
        anchor: x,
        velocity: 0,
        hold: i === 0 ? 0 : random(0.5, 2.5),
        burst: random(0.5, 1.6),
        stretch: 1.12,
      };
    });
  }
  function resize() {
    const nextWidth = Math.round(host.clientWidth),
      nextHeight = Math.round(host.clientHeight);
    if (!nextWidth || !nextHeight || (width === nextWidth && height === nextHeight)) return;
    width = nextWidth;
    height = nextHeight;
    const ratio = Math.min(devicePixelRatio || 1, 1.5);
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
    rivulets.width = canvas.width;
    rivulets.height = canvas.height;
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    condensation.width = width;
    condensation.height = height;
    sunlight.width = width;
    sunlight.height = height;
    optics.width = width;
    optics.height = height;
    if (current !== 'sunny') makeFilm();
    if (current === 'rainy') populate();
    paint();
  }

  function drawRun(d: Run, step: number) {
    if (step) {
      const lastY = d.y;
      if (d.hold > 0) {
        d.hold -= step;
        d.velocity *= Math.exp(-step * 18);
      } else {
        d.velocity = Math.min(110, d.velocity + step * (35 + d.r * 4));
        d.burst -= step;
        if (d.burst <= 0) {
          d.hold = random(0.4, 2.2);
          d.burst = random(0.45, 1.5);
        }
      }
      d.y += d.velocity * step;
      d.x = d.anchor + (noise(d.seed, d.y / 65) - 0.5) * 6;
      d.stretch = 1.08 + Math.min(0.38, d.velocity * 0.004);
      if (d.y > lastY + 0.1) {
        for (let i = drops.length - 1; i >= 0; i--) {
          const bead = drops[i];
          if (
            bead.y >= lastY - d.r &&
            bead.y <= d.y + d.r &&
            Math.abs(bead.x - d.x) < d.r * 0.7 + bead.r
          ) {
            d.r = Math.min(11, Math.sqrt(d.r * d.r + bead.r * bead.r * 0.6));
            d.velocity += bead.r * 1.5;
            drops.splice(i, 1);
          }
        }
        const last = d.trail[d.trail.length - 1];
        if (!last || d.y - last.y > 1.8)
          d.trail.push({
            x: d.x,
            y: d.y,
            time: elapsed,
            r: d.r * (0.3 + noise(d.seed, d.y / 23) * 0.1),
          });
      }
      if (d.y > height + 25) {
        d.y = -15;
        d.r = random(5, 8);
        d.velocity = 0;
        d.hold = random(0.4, 2);
        d.trail = [];
        d.anchor = random(20, width - 20);
      }
      while (d.trail.length && elapsed - d.trail[0].time > 24) d.trail.shift();
    }
    wet.save();
    wet.globalCompositeOperation = 'destination-out';
    wet.lineCap = 'round';
    ctx.save();
    ctx.lineCap = 'round';
    for (let i = 1; i < d.trail.length; i++) {
      const a = d.trail[i - 1],
        b = d.trail[i];
      const fresh = Math.max(0, 1 - (elapsed - b.time) / 24);
      wet.strokeStyle = `rgba(0,0,0,${fresh * 0.14})`;
      wet.lineWidth = b.r * 1.3;
      wet.beginPath();
      wet.moveTo(a.x, a.y);
      wet.lineTo(b.x, b.y);
      wet.stroke();
      // A drained channel has almost no body: only interrupted, subpixel edge glints.
      const glint = (noise(d.seed + 8, b.y / 18) > 0.52 ? 0.045 : 0.012) * fresh;
      ctx.strokeStyle = `rgba(207,225,225,${glint})`;
      ctx.lineWidth = 0.6;
      ctx.beginPath();
      ctx.moveTo(a.x - a.r, a.y);
      ctx.lineTo(b.x - b.r, b.y);
      ctx.stroke();
      ctx.strokeStyle = `rgba(4,17,23,${fresh * 0.035})`;
      ctx.beginPath();
      ctx.moveTo(a.x + a.r, a.y);
      ctx.lineTo(b.x + b.r, b.y);
      ctx.stroke();
    }
    ctx.restore();
    wet.restore();
  }

  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  let frameId = 0,
    previous = 0,
    disposed = false,
    visible = true;
  function paint(step = 0) {
    elapsed += step;
    ctx.clearRect(0, 0, width, height);
    if (current === 'sunny') drawSun();
    else {
      wet.clearRect(0, 0, width, height);
      wet.drawImage(film, 0, 0, width, height);
      if (current === 'rainy') {
        for (const run of runs) drawRun(run, step);
        drawLenses(moisture);
      }
    }
  }
  function frame(now: number) {
    frameId = 0;
    if (disposed || document.hidden || !visible || reduced.matches) return;
    if (now - previous >= 1000 / 30) {
      paint(previous ? Math.min((now - previous) / 1000, 0.05) : 0);
      previous = now;
    }
    frameId = requestAnimationFrame(frame);
  }
  function resume() {
    cancelAnimationFrame(frameId);
    frameId = 0;
    previous = 0;
    if (disposed) return;
    paint();
    if (animated && !document.hidden && visible && !reduced.matches)
      frameId = requestAnimationFrame(frame);
  }
  function ensureWater() {
    if (waterReady || current !== 'rainy') return;
    waterReady = true;
    try {
      initWater();
    } catch {
      releaseWater();
    }
  }
  ensureWater();
  /**
   * 리사이즈를 150ms 늦춘다. 지갑을 여닫으면 유리판이 **매 프레임** 리사이즈되는데,
   * resize() 한 번이 캔버스 여섯 장의 비트맵을 새로 잡아 지우고(DPR 1.5, 950×920 이면
   * water·rivulets 만 각 7.9MB), 끝에서 paint() 가 optics 캔버스 전체를 texImage2D 로
   * GPU 에 올린다. 프레임마다 MB 단위 재할당과 업로드가 반복돼 화면이 멎었다.
   *
   * 캔버스는 CSS 로 width/height 100% 라 백킹 스토어를 안 바꿔도 화면은 늘어난다 —
   * 끄는 동안 살짝 흐릴 뿐이고, 멎으면 한 번에 제 해상도로 돌아온다.
   */
  const resized = new ResizeObserver(() => {
    clearTimeout(resizeTimer);
    resizeTimer = window.setTimeout(resize, 150);
  });
  const intersected = new IntersectionObserver(([entry]) => {
    visible = entry.isIntersecting;
    resume();
  });
  const contextLost = (event: Event) => {
    event.preventDefault();
    drawLenses = () => {};
  };
  canvas.addEventListener('webglcontextlost', contextLost);
  resized.observe(host);
  intersected.observe(host);
  document.addEventListener('visibilitychange', resume);
  reduced.addEventListener('change', resume);
  resize();
  resume();
  return {
    setWeather(next: Weather) {
      if (next === current || disposed) return;
      current = next;
      applyWeather();
      ensureWater();
      // 필름은 이미 있으면 그대로 쓴다(makeFilm 주석). 비면 물방울만 새로 뿌린다.
      if (current !== 'sunny') makeFilm();
      if (current === 'rainy') populate();
      // 전에는 모듈을 헐어 캔버스가 통째로 사라졌다. 이제는 남으므로 직접 지운다.
      else clearWater();
      resume();
    },
    destroy,
  };
  function destroy() {
    disposed = true;
    cancelAnimationFrame(frameId);
    clearTimeout(resizeTimer);
    resized.disconnect();
    intersected.disconnect();
    document.removeEventListener('visibilitychange', resume);
    reduced.removeEventListener('change', resume);
    canvas.removeEventListener('webglcontextlost', contextLost);
    releaseWater();
    host.replaceChildren();
  }
}
