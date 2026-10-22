#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ecommerce-platform 压测器（零依赖，只用 Python 标准库，3.10+）

为什么不用 JMeter 直接跑 docs/benchmark.md 里的方案：
1. 秒杀接口按 userId 去重（lock / ordered / limit / DB count 四道），
   同一个 token 打 5000 并发，999‰ 的请求拿到的是 4003「请勿重复下单」，
   测出来的是拒绝路径的吞吐，不是秒杀链路的吞吐。
   → 本工具为每个并发请求准备一个独立用户，一人一枪。
2. 线程数 ≠ 并发数。线程池在闸门（threading.Barrier）处一起释放，
   形成真正的突发，而不是 ramp-up 期间慢慢爬坡。
3. 库存 100 对并发 5000 时，4900 个请求只会命中「库存已被抢光」，
   测不到 Redis+Lua+MQ 的真实能力。
   → 压测前把 seckill_stock 调到 >= 用户数（见 sql/reset-seckill.sql）。
4. 压完自动做超卖校验：成功数 必须等于 库存消耗量，否则就是扣减和落库不一致。
5. 结果直接产出 Markdown（summary.md），可以粘进 README 的压测数据表。

用法见 tools/loadtest/README.md
"""

from __future__ import annotations

import argparse
import csv
import http.client
import json
import queue
import random
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RESULTS_DIR = ROOT / "results"
DEFAULT_PASSWORD = "Bench@123456"
USER_AGENT = "ecommerce-loadtest/1.0"

# --------------------------------------------------------------------------- #
# 基础数据结构
# --------------------------------------------------------------------------- #


@dataclass
class Reply:
    http: int | None
    body: bytes
    latency_ms: float
    error: str = ""

    def json(self) -> dict | None:
        try:
            value = json.loads(self.body.decode("utf-8", "replace"))
        except Exception:
            return None
        return value if isinstance(value, dict) else None


class Client:
    """带 keep-alive 的极简 HTTP 客户端，单线程持有，不要跨线程共享。"""

    def __init__(self, host: str, port: int, timeout: float):
        self.host = host
        self.port = port
        self.timeout = timeout
        self._conn: http.client.HTTPConnection | None = None

    def connect(self) -> None:
        if self._conn is None:
            self._conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
            self._conn.connect()

    def close(self) -> None:
        if self._conn is not None:
            try:
                self._conn.close()
            except Exception:
                pass
            self._conn = None

    def call(self, method: str, path: str, payload: dict | None = None, token: str | None = None,
             _retry: bool = True) -> Reply:
        headers = {"Accept": "application/json", "Connection": "keep-alive", "User-Agent": USER_AGENT}
        body = None
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        if token:
            headers["Authorization"] = "Bearer " + token

        started = time.perf_counter()
        try:
            if self._conn is None:
                self.connect()
            assert self._conn is not None
            self._conn.request(method, path, body=body, headers=headers)
            resp = self._conn.getresponse()
            raw = resp.read()
            latency = (time.perf_counter() - started) * 1000.0
            if resp.will_close:
                self.close()
            return Reply(resp.status, raw, latency)
        except Exception as exc:  # 连接被服务端关闭 / 超时 / 拒绝连接
            self.close()
            latency = (time.perf_counter() - started) * 1000.0
            # keep-alive 连接被服务端/网关单方面关掉时重连一次，不把这种噪音算成错误率
            transient = isinstance(exc, (http.client.RemoteDisconnected, BrokenPipeError,
                                         ConnectionResetError, ConnectionAbortedError))
            if _retry and transient:
                return self.call(method, path, payload, token, _retry=False)
            return Reply(None, b"", latency, f"{type(exc).__name__}: {exc}")


@dataclass
class Sample:
    bucket: str
    ok: bool
    latency_ms: float
    detail: str = ""


@dataclass
class Cfg:
    base_url: str = "http://localhost:8080"
    host: str = "localhost"
    port: int = 8080
    scenario: str = "seckill"
    users: int = 500
    concurrency: int = 100
    duration: float = 30.0
    activity_id: int = 1
    sku_id: int = 1
    stock: int = 0
    admin_user: str = "admin"
    admin_password: str = "admin123"
    pool: str = ""
    reuse: bool = False
    warm_up: bool = True
    verify_wait: float = 8.0
    spu_min: int = 1
    spu_max: int = 1000
    timeout: float = 15.0
    bootstrap_workers: int = 24
    out: Path = field(default_factory=lambda: RESULTS_DIR / "manual")
    note: str = ""
    weights: dict[str, int] = field(default_factory=lambda: {"spu": 50, "search": 20, "seckill": 15, "page": 10, "login": 5})


# --------------------------------------------------------------------------- #
# 参数解析
# --------------------------------------------------------------------------- #


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="ecommerce-platform 压测器：秒杀链路 / 商品详情 / 混合流量",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""示例：
  # 秒杀：5000 个独立用户，500 线程同时释放（先把库存调到 5000）
  python seckill_bench.py --scenario seckill --users 5000 --concurrency 500

  # 商品详情：64 线程压 30 秒，SPU id 在 1..1000 随机
  python seckill_bench.py --scenario product --concurrency 64 --duration 30

  # 只准备用户、不压测（第一次跑建议先执行，把注册成本从压测里摘出去）
  python seckill_bench.py --users 5000 --bootstrap-only
""",
    )
    p.add_argument("--base-url", default="http://localhost:8080", help="网关地址，默认 http://localhost:8080")
    p.add_argument("--scenario", default="seckill", choices=["seckill", "product", "mixed"], help="压测场景")
    p.add_argument("--users", type=int, default=500, help="秒杀场景的独立用户数（= 请求数），默认 500")
    p.add_argument("--concurrency", type=int, default=100, help="并发线程数，默认 100")
    p.add_argument("--duration", type=float, default=30.0, help="product/mixed 场景压测时长（秒），默认 30")
    p.add_argument("--activity-id", type=int, default=1, help="秒杀活动 ID，默认 1")
    p.add_argument("--sku-id", type=int, default=1, help="秒杀 SKU ID，默认 1")
    p.add_argument("--stock", type=int, default=0, help="预期库存（用于校验），0 表示自动读取")
    p.add_argument("--admin-user", default="admin", help="预热库存用的管理员账号")
    p.add_argument("--admin-password", default="admin123", help="管理员密码")
    p.add_argument("--pool", default="", help="用户池名（默认按时间生成）；同一 pool 可复用账号")
    p.add_argument("--reuse", action="store_true", help="复用同名 pool 的账号（会重新登录刷新 token）")
    p.add_argument("--no-warm-up", action="store_true", help="跳过管理员预热库存")
    p.add_argument("--cold-cache", action="store_true",
                   help="product 场景：不预热缓存，直接压（配合 redis-cli FLUSHDB 做无缓存对照组）")
    p.add_argument("--verify-wait", type=float, default=8.0, help="压完等待 MQ 消费完成再校验库存（秒）")
    p.add_argument("--spu-min", type=int, default=1, help="商品详情场景 SPU id 下界")
    p.add_argument("--spu-max", type=int, default=1000, help="商品详情场景 SPU id 上界")
    p.add_argument("--timeout", type=float, default=15.0, help="单请求超时（秒）")
    p.add_argument("--bootstrap-workers", type=int, default=24, help="注册/登录并发，默认 24")
    p.add_argument("--bootstrap-only", action="store_true", help="只跑注册/登录，不压测")
    p.add_argument("--out", default="", help="结果输出目录，默认 results/<时间戳>-<场景>")
    p.add_argument("--note", default="", help="写进报告备注，例如“本机 16C32G，压测器和被测服务同机”")
    return p


def parse_args(argv: list[str]) -> Cfg:
    ns = build_parser().parse_args(argv)
    cfg = Cfg()
    for key, value in vars(ns).items():
        setattr(cfg, key, value)

    if not cfg.base_url.startswith("http://"):
        raise SystemExit("只支持 http://（本机压测不需要 TLS，也避免证书干扰）")
    rest = cfg.base_url[len("http://"):].rstrip("/")
    host, _, port = rest.partition(":")
    cfg.host = host or "localhost"
    cfg.port = int(port) if port else 80

    cfg.pool = cfg.pool or "p" + datetime.now().strftime("%m%d%H%M")
    if not cfg.out:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        cfg.out = RESULTS_DIR / f"{stamp}-{cfg.scenario}"
    cfg.out = Path(cfg.out)
    return cfg


# --------------------------------------------------------------------------- #
# 小工具
# --------------------------------------------------------------------------- #


def now_str() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def say(msg: str) -> None:
    print(msg, flush=True)


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    data = sorted(values)
    k = (len(data) - 1) * p / 100.0
    lo = int(k)
    hi = min(lo + 1, len(data) - 1)
    return round(data[lo] + (data[hi] - data[lo]) * (k - lo), 1)


def latency_block(values: list[float]) -> dict[str, float]:
    if not values:
        return {"avg": 0.0, "p50": 0.0, "p90": 0.0, "p95": 0.0, "p99": 0.0, "max": 0.0}
    return {
        "avg": round(sum(values) / len(values), 1),
        "p50": percentile(values, 50),
        "p90": percentile(values, 90),
        "p95": percentile(values, 95),
        "p99": percentile(values, 99),
        "max": round(max(values), 1),
    }


def classify(reply: Reply, kind: str) -> Sample:
    """把 HTTP 响应翻译成「成功 / 业务拒绝 / 网络错误」三类，按业务码归桶。"""
    if reply.error:
        return Sample("网络异常", False, reply.latency_ms, reply.error)

    payload = reply.json()
    if payload is None:
        return Sample(f"HTTP {reply.http} 非 JSON", False, reply.latency_ms, reply.body[:120].decode("utf-8", "replace"))

    code = payload.get("code")
    msg = payload.get("msg") or ""
    data = payload.get("data")

    if kind == "seckill":
        queued = isinstance(data, dict) and data.get("code") == 1
        if reply.http == 200 and code == 200 and queued:
            return Sample("成功(排队)", True, reply.latency_ms, str((data or {}).get("msg", "")))
        bucket = f"{code} {msg}".strip() if code is not None else f"HTTP {reply.http}"
        return Sample(bucket, False, reply.latency_ms, bucket)

    if reply.http == 200 and code == 200:
        return Sample("成功", True, reply.latency_ms, "")
    bucket = f"{code} {msg}".strip() if code is not None else f"HTTP {reply.http}"
    return Sample(bucket, False, reply.latency_ms, bucket)


def summarize(samples: list[Sample], window_s: float) -> dict:
    total = len(samples)
    ok = [s for s in samples if s.ok]
    buckets: dict[str, int] = {}
    for s in samples:
        buckets[s.bucket] = buckets.get(s.bucket, 0) + 1
    buckets = dict(sorted(buckets.items(), key=lambda kv: kv[1], reverse=True))

    return {
        "total": total,
        "success": len(ok),
        "error_rate": round((total - len(ok)) / total * 100, 2) if total else 0.0,
        "qps": round(total / window_s, 1) if window_s > 0 else 0.0,
        "window_s": round(window_s, 3),
        "latency_ms": latency_block([s.latency_ms for s in samples]),
        "success_latency_ms": latency_block([s.latency_ms for s in ok]),
        "buckets": buckets,
    }


# --------------------------------------------------------------------------- #
# 用户准备：每个用户一个 token（这是原方案最缺的一环）
# --------------------------------------------------------------------------- #


def _thread_client(cfg: Cfg, store: threading.local) -> Client:
    client = getattr(store, "client", None)
    if client is None:
        client = Client(cfg.host, cfg.port, cfg.timeout)
        client.connect()
        store.client = client
    return client


def bootstrap_users(cfg: Cfg, cache_file: Path) -> list[dict]:
    """注册（或复用后登录）cfg.users 个用户，返回 [{username, password, userId, token}]。"""
    cached: dict[str, dict] = {}
    if cfg.reuse and cache_file.exists():
        try:
            for item in json.loads(cache_file.read_text(encoding="utf-8")):
                cached[item["username"]] = item
        except Exception:
            cached = {}

    names = [f"{cfg.pool}{i:05d}" for i in range(cfg.users)]
    users: list[dict | None] = [None] * len(names)
    store = threading.local()
    lock = threading.Lock()
    done = {"n": 0}
    failures: list[str] = []

    def prepare(index: int, username: str) -> None:
        client = _thread_client(cfg, store)
        password = DEFAULT_PASSWORD
        if username in cached:
            # 复用：直接登录刷新 token
            reply = client.call("POST", "/api/user/login", {"username": username, "password": cached[username].get("password", password)})
            payload = reply.json() or {}
            data = payload.get("data") or {}
            if payload.get("code") == 200 and data.get("accessToken"):
                users[index] = {"username": username, "password": password, "userId": data.get("userId"), "token": data["accessToken"]}
            else:
                failures.append(f"login {username}: {payload.get('code')} {payload.get('msg')} {reply.error}")
        else:
            reply = client.call("POST", "/api/user/register", {"username": username, "password": password})
            payload = reply.json() or {}
            data = payload.get("data") or {}
            if payload.get("code") == 200 and data.get("accessToken"):
                users[index] = {"username": username, "password": password, "userId": data.get("userId"), "token": data["accessToken"]}
            elif payload.get("code") == 1004:  # 账号已存在 → 登录
                reply = client.call("POST", "/api/user/login", {"username": username, "password": password})
                payload = reply.json() or {}
                data = payload.get("data") or {}
                if payload.get("code") == 200 and data.get("accessToken"):
                    users[index] = {"username": username, "password": password, "userId": data.get("userId"), "token": data["accessToken"]}
                else:
                    failures.append(f"login {username}: {payload.get('code')} {payload.get('msg')}")
            else:
                failures.append(f"register {username}: {payload.get('code')} {payload.get('msg')} {reply.error}")

        with lock:
            done["n"] += 1
            if done["n"] % 500 == 0 or done["n"] == len(names):
                say(f"  用户准备 {done['n']}/{len(names)}")

    say(f"[3/5] 准备 {len(names)} 个用户（pool={cfg.pool}，并发 {cfg.bootstrap_workers}）…")
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=cfg.bootstrap_workers) as pool:
        for i, name in enumerate(names):
            pool.submit(prepare, i, name)
    elapsed = time.perf_counter() - started

    ready = [u for u in users if u]
    say(f"  完成 {len(ready)}/{len(names)}，耗时 {elapsed:.1f}s" + (f"，失败 {len(failures)}" if failures else ""))
    if failures:
        for line in failures[:5]:
            say(f"  ! {line}")
    if len(ready) < len(names) * 0.95:
        raise SystemExit("用户准备失败率过高，先确认 /api/user/register 与 /api/user/login 正常")

    cached_out = [
        {"username": u["username"], "password": u["password"], "userId": u["userId"], "token": u["token"]}
        for u in ready
    ]
    cache_file.parent.mkdir(parents=True, exist_ok=True)
    cache_file.write_text(json.dumps(cached_out, ensure_ascii=False), encoding="utf-8")
    return ready


# --------------------------------------------------------------------------- #
# 两种压测执行器
# --------------------------------------------------------------------------- #


def run_burst(cfg: Cfg, users: list[dict]) -> tuple[list[Sample], float, list[str]]:
    """突发模式：N 个线程在闸门处一起释放，每个线程取一个用户、打一枪。"""
    workers = max(1, min(cfg.concurrency, len(users)))
    task_queue: queue.Queue[dict] = queue.Queue()
    for user in users:
        task_queue.put(user)

    barrier = threading.Barrier(workers + 1)
    samples: list[Sample] = []
    connect_errors: list[str] = []
    lock = threading.Lock()

    def worker() -> None:
        client = Client(cfg.host, cfg.port, cfg.timeout)
        try:
            client.connect()
        except Exception as exc:
            with lock:
                connect_errors.append(f"建连失败: {exc}")
            barrier.wait()  # 保证不放 main 鸽子
            return

        try:
            barrier.wait()  # ← 所有线程在这里对齐，同时起跑
            while True:
                try:
                    user = task_queue.get_nowait()
                except queue.Empty:
                    return
                reply = client.call(
                    "POST",
                    "/api/seckill",
                    {"activityId": cfg.activity_id, "skuId": cfg.sku_id},
                    token=user["token"],
                )
                sample = classify(reply, "seckill")
                with lock:
                    samples.append(sample)
        finally:
            client.close()

    threads = [threading.Thread(target=worker, name=f"burst-{i}", daemon=True) for i in range(workers)]
    for t in threads:
        t.start()

    barrier.wait()  # 起跑：所有线程同时离开闸门
    started = time.perf_counter()  # 计时从「真正开跑」开始，不含建连与线程启动
    for t in threads:
        t.join()
    window = time.perf_counter() - started
    return samples, window, connect_errors


def run_loop(
    cfg: Cfg,
    workers: int,
    task,
) -> tuple[list[Sample], float, list[str]]:
    """固定并发模式：线程不停地打，打满 duration 秒。"""
    stop = threading.Event()
    samples: list[Sample] = []
    connect_errors: list[str] = []
    lock = threading.Lock()

    def worker(index: int) -> None:
        client = Client(cfg.host, cfg.port, cfg.timeout)
        try:
            client.connect()
        except Exception as exc:
            with lock:
                connect_errors.append(f"建连失败: {exc}")
            return
        try:
            while not stop.is_set():
                sample = task(client, index)
                with lock:
                    samples.append(sample)
        finally:
            client.close()

    threads = [threading.Thread(target=worker, args=(i,), name=f"loop-{i}", daemon=True) for i in range(workers)]
    started = time.perf_counter()
    for t in threads:
        t.start()
    time.sleep(cfg.duration)
    stop.set()
    for t in threads:
        t.join()
    window = time.perf_counter() - started
    return samples, window, connect_errors


# --------------------------------------------------------------------------- #
# 业务动作
# --------------------------------------------------------------------------- #


def product_task(cfg: Cfg):
    def task(client: Client, _index: int) -> Sample:
        spu_id = random.randint(cfg.spu_min, cfg.spu_max)
        return classify(client.call("GET", f"/api/product/spu/{spu_id}"), "product")

    return task


def mixed_task(cfg: Cfg, tokens: list[str]):
    token_queue: queue.Queue[str] = queue.Queue()
    for token in tokens:
        token_queue.put(token)
    pool_lock = threading.Lock()
    weights = cfg.weights
    kinds = list(weights.keys())
    cumulative = []
    running = 0
    for kind in kinds:
        running += weights[kind]
        cumulative.append(running)

    def pick() -> str:
        point = random.randint(1, cumulative[-1])
        for kind, edge in zip(kinds, cumulative):
            if point <= edge:
                return kind
        return kinds[-1]

    def task(client: Client, _index: int) -> Sample:
        kind = pick()
        if kind == "spu":
            return classify(client.call("GET", f"/api/product/spu/{random.randint(cfg.spu_min, cfg.spu_max)}"), "product")
        if kind == "search":
            return classify(client.call("POST", "/api/product/search", {"keyword": "iPhone", "page": 1, "size": 10}), "product")
        if kind == "page":
            return classify(client.call("GET", "/api/product/spu/page?page=1&size=10"), "product")
        if kind == "login":
            return classify(
                client.call("POST", "/api/user/login", {"username": cfg.admin_user, "password": cfg.admin_password}),
                "product",
            )
        # seckill：每个用户只能用一次，领不到 token 就退化成浏览
        try:
            with pool_lock:
                token = token_queue.get_nowait()
        except queue.Empty:
            return classify(client.call("GET", f"/api/product/spu/{random.randint(cfg.spu_min, cfg.spu_max)}"), "product")
        return classify(
            client.call("POST", "/api/seckill", {"activityId": cfg.activity_id, "skuId": cfg.sku_id}, token=token),
            "seckill",
        )

    return task


# --------------------------------------------------------------------------- #
# 预检 / 预热 / 校验
# --------------------------------------------------------------------------- #


def preflight(cfg: Cfg) -> Client:
    client = Client(cfg.host, cfg.port, cfg.timeout)
    try:
        client.connect()
    except Exception as exc:
        raise SystemExit(
            f"连不上网关 {cfg.base_url}：{exc}\n"
            "先确认中间件和 7 个服务都起来了：docker compose ps / start-all.bat"
        )
    reply = client.call("GET", "/api/seckill/activities")
    payload = reply.json()
    if payload is None or payload.get("code") != 200:
        raise SystemExit(f"网关探活失败：HTTP {reply.http} {payload} {reply.error}")
    say(f"[1/5] 网关可用：{cfg.base_url}（GET /api/seckill/activities 正常）")
    return client


def admin_token(cfg: Cfg, client: Client) -> str:
    reply = client.call("POST", "/api/user/login", {"username": cfg.admin_user, "password": cfg.admin_password})
    payload = reply.json() or {}
    data = payload.get("data") or {}
    if payload.get("code") != 200 or not data.get("accessToken"):
        raise SystemExit(f"管理员登录失败：{payload.get('code')} {payload.get('msg')} {reply.error}")
    return data["accessToken"]


def warm_up(cfg: Cfg, client: Client, token: str) -> None:
    reply = client.call("POST", f"/api/seckill/warm-up/{cfg.activity_id}", token=token)
    payload = reply.json() or {}
    if payload.get("code") != 200:
        raise SystemExit(f"库存预热失败：{payload.get('code')} {payload.get('msg')} {reply.error}")
    say(f"[2/5] 库存预热完成（activity={cfg.activity_id}）")


def read_stock(cfg: Cfg, client: Client) -> int:
    reply = client.call("GET", f"/api/seckill/stock/{cfg.activity_id}/{cfg.sku_id}")
    payload = reply.json() or {}
    try:
        return int(payload.get("data"))
    except (TypeError, ValueError):
        raise SystemExit(f"读取实时库存失败：{payload} {reply.error}")


def verify_stock(cfg: Cfg, client: Client, initial: int, success: int) -> dict:
    say(f"[5/5] 等 {cfg.verify_wait:.0f}s 让 MQ 消费完成，再校验库存与超卖…")
    time.sleep(cfg.verify_wait)
    final = read_stock(cfg, client)
    for _ in range(10):
        time.sleep(1.0)
        latest = read_stock(cfg, client)
        if latest == final:
            break
        final = latest

    consumed = initial - final
    return {
        "initial_stock": initial,
        "final_stock": final,
        "consumed": consumed,
        "success_requests": success,
        "oversold": success > consumed,
        "mismatch": success != consumed,
        "lost_update": consumed > success,
    }


# --------------------------------------------------------------------------- #
# 报告
# --------------------------------------------------------------------------- #


def write_report(cfg: Cfg, stats: dict, extra: dict, samples: list[Sample], run_dir: Path) -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    with (run_dir / "raw.csv").open("w", newline="", encoding="utf-8-sig") as fh:
        writer = csv.writer(fh)
        writer.writerow(["bucket", "ok", "latency_ms", "detail"])
        for s in samples:
            writer.writerow([s.bucket, int(s.ok), f"{s.latency_ms:.1f}", s.detail[:200]])

    payload = {
        "scenario": cfg.scenario,
        "target": cfg.base_url,
        "started_at": now_str(),
        "params": {
            "users": cfg.users,
            "concurrency": cfg.concurrency,
            "duration_s": cfg.duration,
            "activity_id": cfg.activity_id,
            "sku_id": cfg.sku_id,
            "spu_range": [cfg.spu_min, cfg.spu_max],
        },
        "stats": stats,
        "extra": extra,
        "note": cfg.note,
    }
    (run_dir / "summary.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    lat = stats["latency_ms"]
    ok_lat = stats["success_latency_ms"]
    lines = [
        f"# 压测结果 · {cfg.scenario}",
        "",
        f"- 时间：{payload['started_at']}",
        f"- 目标：{cfg.base_url}",
        f"- 并发线程：{cfg.concurrency}　总请求：{stats['total']}　窗口：{stats['window_s']}s",
        f"- 成功：{stats['success']}　错误率：{stats['error_rate']}%　吞吐：{stats['qps']} req/s",
        f"- 备注：{cfg.note or '无'}",
        "",
        "## 响应时间（ms）",
        "",
        "| 口径 | avg | p50 | p90 | p95 | p99 | max |",
        "|------|-----|-----|-----|-----|-----|-----|",
        f"| 全部请求 | {lat['avg']} | {lat['p50']} | {lat['p90']} | {lat['p95']} | {lat['p99']} | {lat['max']} |",
        f"| 仅成功请求 | {ok_lat['avg']} | {ok_lat['p50']} | {ok_lat['p90']} | {ok_lat['p95']} | {ok_lat['p99']} | {ok_lat['max']} |",
        "",
        "## 结果分布",
        "",
        "| 结果 | 次数 | 占比 |",
        "|------|------|------|",
    ]
    for bucket, count in stats["buckets"].items():
        pct = round(count / stats["total"] * 100, 2) if stats["total"] else 0.0
        lines.append(f"| {bucket} | {count} | {pct}% |")

    if extra:
        labels = {
            "initial_stock": "初始库存",
            "final_stock": "剩余库存",
            "consumed": "库存消耗",
            "success_requests": "成功请求数",
            "oversold": "是否超卖",
            "mismatch": "成功数与消耗是否不一致",
            "lost_update": "扣减多于成功（疑似 MQ 丢消息）",
            "connect_errors": "建连失败次数",
            "网络错误": "网络错误",
        }
        lines += ["", "## 校验", ""]
        for key, value in extra.items():
            lines.append(f"- {labels.get(key, key)}: {value}")

        consumed = extra.get("consumed")
        success = extra.get("success_requests")
        if consumed is not None and success is not None:
            verdict = "通过：成功数 = 库存消耗量" if success == consumed else "不一致：见上方 mismatch / oversold / lost_update"
            lines += ["", f"**超卖校验：{verdict}**"]

    concurrency_label = cfg.users if cfg.scenario == "seckill" else cfg.concurrency
    lines += [
        "",
        "## 可直接粘进 README 的一行",
        "",
        "```markdown",
        f"| {cfg.scenario} | {concurrency_label} | {stats['qps']} | {lat['p99']} ms | {stats['error_rate']}% | {now_str()} 本机实测 |",
        "```",
        "",
        "> 口径：每秒请求数 = 总请求 / 压测窗口；P99 为全部请求的 99 分位；错误率 = 非成功响应 / 总请求。",
    ]
    (run_dir / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    say("")
    say("────────────── 压测结果 ──────────────")
    say(f"  请求总数   {stats['total']}    成功 {stats['success']}    错误率 {stats['error_rate']}%")
    say(f"  吞吐       {stats['qps']} req/s（窗口 {stats['window_s']}s）")
    say(f"  RT         avg {lat['avg']}ms  p90 {lat['p90']}ms  p95 {lat['p95']}ms  p99 {lat['p99']}ms  max {lat['max']}ms")
    say("  分布：")
    for bucket, count in stats["buckets"].items():
        share = round(count / stats["total"] * 100, 2) if stats["total"] else 0.0
        say(f"    {bucket:<28} {count:>8}  ({share}%)")
    if extra:
        if "initial_stock" in extra:
            say(f"  库存：初始 {extra.get('initial_stock')} → 剩余 {extra.get('final_stock')}（消耗 {extra.get('consumed')}），"
                f"成功请求 {extra.get('success_requests')}")
            if extra.get("oversold"):
                say("  ❌ 超卖：成功数 > 库存消耗量")
            elif extra.get("lost_update"):
                say("  ⚠️ 扣了库存但成功数更少：检查 Lua 返回值和 MQ 发送是否吞异常")
            else:
                say("  ✅ 未超卖：成功数 = 库存消耗量")
        if extra.get("connect_errors"):
            say(f"  ⚠️ 建连失败 {extra['connect_errors']} 次（先在压测机上确认端口/连接数上限）")
    say(f"  报告：{run_dir / 'summary.md'}")
    say("──────────────────────────────────────")


# --------------------------------------------------------------------------- #
# 主流程
# --------------------------------------------------------------------------- #


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass

    cfg = parse_args(argv if argv is not None else sys.argv[1:])
    say(f"ecommerce-platform 压测器 · 场景={cfg.scenario} · 目标={cfg.base_url}")

    client = preflight(cfg)
    try:
        if cfg.scenario == "product":
            probe = classify(client.call("GET", f"/api/product/spu/{cfg.spu_max}"), "product")
            if not probe.ok:
                say(f"  ⚠️ 取 SPU {cfg.spu_max} 返回 {probe.bucket}：商品数据不够，"
                    "先跑 sql/seed-products.sql 灌 1000 条，否则错误率会全是 2001 商品不存在")
            if cfg.cold_cache:
                say(f"[2/3] 冷缓存模式：不预热，直接压 {cfg.duration:.0f}s"
                    f"（{cfg.concurrency} 线程，SPU {cfg.spu_min}-{cfg.spu_max}）…")
            else:
                say(f"[2/3] 预热缓存并压 {cfg.duration:.0f}s（{cfg.concurrency} 线程，SPU {cfg.spu_min}-{cfg.spu_max}）…")
                warm = Client(cfg.host, cfg.port, cfg.timeout)
                warm.connect()
                for _ in range(200):
                    warm.call("GET", f"/api/product/spu/{random.randint(cfg.spu_min, cfg.spu_max)}")
                warm.close()
            task = product_task(cfg)
            samples, window, errors = run_loop(cfg, cfg.concurrency, task)
            stats = summarize(samples, window)
            write_report(cfg, stats, {"网络错误": len(errors)}, samples, cfg.out)
            return 0 if not errors else 0

        # seckill / mixed
        admin = admin_token(cfg, client)
        if not cfg.no_warm_up:
            warm_up(cfg, client, admin)
            initial = read_stock(cfg, client)
            say(f"  当前 Redis 库存：{initial}")
        else:
            initial = read_stock(cfg, client)
            say(f"[3/5] 跳过热身，当前 Redis 库存：{initial}")

        if cfg.stock and initial != cfg.stock:
            say(f"  ⚠️ 库存是 {initial}，不是指定的 {cfg.stock}："
                f"先把 db_seckill.t_seckill_product.seckill_stock 改成 {cfg.stock}，再重新预热")

        users = bootstrap_users(cfg, RESULTS_DIR / f"_pool_{cfg.pool}.json")
        if cfg.bootstrap_only:
            say("[4/5] --bootstrap-only：用户已就绪，退出")
            return 0

        if cfg.scenario == "seckill":
            if initial < len(users):
                say(f"  ⚠️ 库存 {initial} < 用户数 {len(users)}：会有 {len(users) - initial} 个请求命中 4004，"
                    "这部分是正确拒绝，不是性能问题；要测满负荷请把库存调到 >= 用户数")
            say(f"[4/5] 突发开压：{len(users)} 个用户 / {min(cfg.concurrency, len(users))} 线程同时释放…")
            samples, window, connect_errors = run_burst(cfg, users)
            stats = summarize(samples, window)
            check = verify_stock(cfg, client, initial, stats["success"])
            check["connect_errors"] = len(connect_errors)
            write_report(cfg, stats, check, samples, cfg.out)
            return 1 if check["oversold"] else 0

        # mixed
        tokens = [u["token"] for u in users]
        say(f"[3/3] 混合流量 {cfg.duration:.0f}s：{cfg.concurrency} 线程，权重 {cfg.weights}")
        samples, window, connect_errors = run_loop(cfg, cfg.concurrency, mixed_task(cfg, tokens))
        stats = summarize(samples, window)
        write_report(cfg, stats, {"网络错误": len(connect_errors)}, samples, cfg.out)
        return 0
    finally:
        client.close()


if __name__ == "__main__":
    raise SystemExit(main())
