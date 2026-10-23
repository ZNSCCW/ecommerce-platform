# JMeter 对照计划（可选）

这个目录是为了「用专业工具验证自研脚本」而留的，不是日常压测入口。
日常压测请用 `tools/loadtest/seckill_bench.py`（见 `tools/loadtest/README.md`）。

## 为什么留这份

`docs/benchmark.md` §8.1 记录了 2026-10-23 的对照实验：同机、同数据、同参数下，
JMeter 与自研脚本的有效量级一致（中位数 584 vs 715 TPS，差 10~20%），
用来证明「自研脚本的数字不是自己编的」。

## 怎么跑

```powershell
# 1. 先把用户池的 token 导出成 CSV（用户池由自研脚本 bootstrap 生成）
python -c "import json,csv; u=json.load(open('tools/loadtest/results/_pool_jm.json',encoding='utf-8')); w=csv.writer(open('tools/loadtest/jmeter/tokens.csv','w',newline='',encoding='utf-8')); w.writerow(['token']); [w.writerow([x['token']]) for x in u]"

# 2. 必须先预热库存，JMeter 不会自己做，否则全是 4004
curl -X POST http://localhost:8080/api/seckill/warm-up/1 -H "Authorization: Bearer <admin token>"

# 3. 非 GUI 跑（在本目录下执行，tokens.csv 用的是相对路径）
jmeter -n -t seckill.jmx -l result.jtl

# 4. 统计（注意 subresults=true 时 JTL 会重复计数，要按 timestamp+threadName+elapsed 去重）
```

## 两个坑（实测踩过）

1. **断言很贵**：加 `JSR223 Assertion`（Groovy）和 `subresults=true` 后，同场景从 584 TPS 掉到 282 TPS。
   同机压测时工具开销会直接压低被测指标，正式对照请用这份精简版（无断言，靠服务端订单数校验正确性）。
2. **JTL 重复行**：`subresults=true` 会把断言结果也写一行，1000 个样本变成 2000 行，TPS 会算错一倍。

## 计划说明

- 线程组：200 线程、Ramp-up 0、循环 5（200 × 5 = 1000 个请求，每个请求消费一行 CSV，`recycle=false`）
- 每个请求一个独立 token，对应「一人一单」语义
- 结果写入 `result.jtl`，字段含 timestamp / elapsed / latency / success / connect
