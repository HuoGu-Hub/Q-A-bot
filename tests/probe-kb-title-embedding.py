"""探针：标题要不要进向量？—— 离线对比几种召回落法（不参与线上逻辑）。

背景（2026-10-01 的观察）：
    语料里 94% 的块标题是「中文名（English）」这种物品名，正文是简介，
    而正文里**几乎不会重复标题里的名字**。但块的向量是对 *正文* 算的
    （KbDocImporter:95 / KbBlockAdminService:160 都是 embedOne(body)），
    于是"用名字提问"时，文档向量里根本没有这个名字 —— A 路只能靠语义擦边，
    实测「什么是酸蚀之咬」本体排第 7（余弦 0.492，阈值 0.45），
    前面全是同族词（永恒酸蚀咬击 0.582、酸蚀砍刀 0.540…）。

本脚本回答三个问题：
    1. 把标题拼进被嵌入的文本（方案 1）能把本体抬到第几？
    2. 正文向量留着、只给标题补一条向量（方案 2，零重建成本）够不够？
    3. 不花一分钱的标题字面命中（方案 3/4）能覆盖多少？

做法：从「中文名（English）」型块里抽 N 个当 ground truth，
    查询 = 中文名（模拟"用户打物品名"），比较各方案下正确块的名次。

用法：
    python3 tests/probe-kb-title-embedding.py            # 默认 200 条
    python3 tests/probe-kb-title-embedding.py -n 500
    python3 tests/probe-kb-title-embedding.py --db /path/qqbot.sqlite

⚠️ 容器里必须先把库**复制**出来再跑：
    宿主机跑着服务时 qqbot.sqlite 是 WAL 模式，经 Docker Desktop 的挂载层
    直接读会报 "sqlite3.OperationalError: disk I/O error"。
    cp server/data/qa/qqbot.sqlite* /tmp/kb2/ && \
      python3 tests/probe-kb-title-embedding.py --db /tmp/kb2/qqbot.sqlite

成本：全量标题（6.1 万字）+ 全量 title+body（30 万字）各嵌一次，
    bge-m3 约 0.5 元/百万 token，整轮 < 0.5 元。结果缓存在 /tmp，重跑不再花钱。
"""
import argparse
import json
import math
import os
import pickle
import random
import re
import sqlite3
import struct
import sys
import time
import urllib.error
import urllib.request

ENV = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '.env')
MODEL = 'BAAI/bge-m3'
URL = 'https://api.siliconflow.cn/v1/embeddings'
BATCH = 16
CACHE = '/tmp/kb-title-embedding-cache.pkl'

TITLE_PAT = re.compile(r'^([^（(]{2,14})[（(]([A-Za-z][^）)]*)[）)]$')


def api_key():
    with open(ENV, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line.startswith('SILICONFLOW_API_KEY='):
                v = line.split('=', 1)[1].strip().strip('"').strip("'")
                if v:
                    return v
    raise SystemExit('没有找到 SILICONFLOW_API_KEY')


def embed_batch(key, texts, tries=4):
    """一次请求嵌一批；失败重试（含 429 限流）。"""
    body = json.dumps({'model': MODEL, 'input': texts, 'encoding_format': 'float'}).encode()
    for attempt in range(tries):
        req = urllib.request.Request(URL, data=body, headers={
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + key,
        })
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                data = json.load(resp)['data']
            data.sort(key=lambda d: d.get('index', 0))
            return [d['embedding'] for d in data]
        except urllib.error.HTTPError as e:
            detail = e.read().decode('utf-8', 'ignore')[:200]
            wait = 2 ** attempt
            print('    ⚠️ HTTP %s：%s（%ds 后重试）' % (e.code, detail, wait), flush=True)
            time.sleep(wait)
        except Exception as e:  # noqa: BLE001
            wait = 2 ** attempt
            print('    ⚠️ %s（%ds 后重试）' % (e, wait), flush=True)
            time.sleep(wait)
    raise SystemExit('嵌入连续失败，中止')


def embed_all(key, texts, label):
    out = []
    total = len(texts)
    for i in range(0, total, BATCH):
        chunk = texts[i:i + BATCH]
        out.extend(embed_batch(key, chunk))
        if (i // BATCH) % 20 == 0:
            print('    [%s] %d/%d' % (label, min(i + BATCH, total), total), flush=True)
    print('    [%s] 完成 %d 条' % (label, len(out)), flush=True)
    return out


def norm(v):
    return math.sqrt(sum(x * x for x in v))


def cosine(q, v, qn, vn):
    return sum(a * b for a, b in zip(q, v)) / (qn * vn)


def load_blocks(db):
    con = sqlite3.connect('file:%s?mode=ro' % db, uri=True)
    cur = con.cursor()
    vecs = {}
    for bid, blob in cur.execute('select id, vec from kb_block_vec'):
        # KbBlockStore 用 BIG_ENDIAN float32 存（KbBlockStore:259）
        vecs[bid] = struct.unpack('>%df' % (len(blob) // 4), blob)
    blocks = []
    for bid, doc, title, body, retired in cur.execute(
            'select id, doc_id, title, body, retired from kb_block'):
        if retired or bid not in vecs:
            continue
        blocks.append({'id': bid, 'doc': doc, 'title': title, 'body': body, 'vec': vecs[bid]})
    con.close()
    return blocks


def build_queries(blocks, n, seed=42):
    """抽「中文名（English）」型块当 ground truth；查询 = 中文名。"""
    pool = []
    seen = set()
    for b in blocks:
        m = TITLE_PAT.match(b['title'].strip())
        if not m:
            continue
        head = m.group(1).strip()
        if len(head) < 2 or head in seen:
            continue
        seen.add(head)
        pool.append((head, b))
    random.Random(seed).shuffle(pool)
    pool = pool[:n]
    titles = {}
    for b in blocks:
        m = TITLE_PAT.match(b['title'].strip())
        if m:
            titles.setdefault(m.group(1).strip(), set()).add(b['id'])
    return [{'q': head, 'q2': '什么是' + head, 'targets': titles[head], 'title': b['title']}
            for head, b in pool]


def rank_of(targets, order):
    """正确块（同名的任一）在排序里的名次；不在表里返回 None。"""
    for i, bid in enumerate(order):
        if bid in targets:
            return i + 1
    return None


def metrics(ranks, extra=None):
    got = [r for r in ranks if r]
    m = {
        'n': len(ranks),
        'found': len(got),
        'r@1': sum(1 for r in got if r <= 1) / len(ranks),
        'r@5': sum(1 for r in got if r <= 5) / len(ranks),
        'r@20': sum(1 for r in got if r <= 20) / len(ranks),
        'mrr': sum(1.0 / r for r in got) / len(ranks),
    }
    if extra:
        m.update(extra)
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('-n', type=int, default=200)
    ap.add_argument('--db', default='/tmp/kb2/qqbot.sqlite')
    ap.add_argument('--no-cache', action='store_true')
    args = ap.parse_args()

    if not os.path.exists(args.db):
        raise SystemExit('库不存在：%s —— 先把 qqbot.sqlite* 复制到 /tmp/kb2/（见文件头注释）' % args.db)

    key = api_key()
    blocks = load_blocks(args.db)
    print('块 %d 个（未下架且有向量）' % len(blocks), flush=True)

    cases = build_queries(blocks, args.n)
    print('测试集 %d 条（查询 = 标题里的中文名）' % len(cases), flush=True)

    cache = {}
    if os.path.exists(CACHE) and not args.no_cache:
        with open(CACHE, 'rb') as f:
            cache = pickle.load(f)
        print('命中缓存：%s' % CACHE, flush=True)

    need = {
        'titles': [b['title'] for b in blocks],
        'titlebody': [b['title'] + '\n' + b['body'] for b in blocks],
        'queries': [c['q'] for c in cases] + [c['q2'] for c in cases],
    }
    for k, texts in need.items():
        if k not in cache or len(cache[k]) != len(texts):
            print('==> 嵌入 %s（%d 条）' % (k, len(texts)), flush=True)
            cache[k] = embed_all(key, texts, k)
        else:
            print('==> %s 用缓存（%d 条）' % (k, len(texts)), flush=True)
    with open(CACHE, 'wb') as f:
        pickle.dump(cache, f)

    vtitle = {b['id']: v for b, v in zip(blocks, cache['titles'])}
    vtb = {b['id']: v for b, v in zip(blocks, cache['titlebody'])}
    vbody = {b['id']: b['vec'] for b in blocks}
    ids = [b['id'] for b in blocks]
    tb_norm = {i: norm(vtb[i]) for i in ids}
    ti_norm = {i: norm(vtitle[i]) for i in ids}
    bo_norm = {i: norm(vbody[i]) for i in ids}

    nq = len(cases)
    qs = cache['queries'][:nq]
    qs2 = cache['queries'][nq:]

    def rank_all(get_cos):
        out = []
        for i, c in enumerate(cases):
            order = sorted(ids, key=lambda bid: -get_cos(bid, i))
            out.append(rank_of(c['targets'], order))
        return out

    def cos_body(bid, i):
        return cosine(qs[i], vbody[bid], qn[i], bo_norm[bid])

    def cos_tb(bid, i):
        return cosine(qs[i], vtb[bid], qn[i], tb_norm[bid])

    def cos_title(bid, i):
        return cosine(qs[i], vtitle[bid], qn[i], ti_norm[bid])

    qn = [norm(q) for q in qs]
    qn2 = [norm(q) for q in qs2]

    results = {}
    print('\n===== 结果（查询 = 纯中文名）=====', flush=True)

    def add(name, ranks, extra=None):
        m = metrics(ranks, extra)
        results[name] = m
        print('%-26s R@1 %5.1f%%  R@5 %5.1f%%  R@20 %5.1f%%  MRR %.3f%s'
              % (name, m['r@1'] * 100, m['r@5'] * 100, m['r@20'] * 100, m['mrr'],
                 ('  ' + json.dumps(extra, ensure_ascii=False)) if extra else ''), flush=True)

    # ① 现状：正文向量
    ranks_body = rank_all(cos_body)
    hit = [r for r in ranks_body if r]
    add('① 现状 body', ranks_body)

    # ② 方案 1：title+body 重建
    add('② 方案1 title+body 重建', rank_all(cos_tb))

    # ③ 只给标题补一条向量
    add('③ 方案2 仅标题向量', rank_all(cos_title))

    # ④ 方案 2：max(body, title)
    add('④ 方案2 max(body,title)', rank_all(lambda bid, i: max(cos_body(bid, i), cos_title(bid, i))))

    # ⑤ 方案 2：body + w*title
    for w in (0.5, 1.0, 1.5):
        add('⑤ 方案2 body+%.1f*title' % w,
            rank_all(lambda bid, i, w=w: cos_body(bid, i) + w * cos_title(bid, i)))

    # ⑥ 免费兜底：标题字面命中（查询串出现在标题里）
    cjk = [c for c in cases]
    lit = []
    for c in cjk:
        head = c['q']
        order = [b['id'] for b in blocks if head in b['title']] + \
                [b['id'] for b in blocks if head not in b['title']]
        lit.append(rank_of(c['targets'], order))
    add('⑥ 兜底 标题字面命中', lit)

    # ⑦ 方案 2 的生产形态：标题向量单独成一路（C 路），与正文路（A 路）做 RRF。
    #    B 路不受本方案影响（它靠术语表字面匹配），所以这里只比 A vs A+C，效果正交。
    def rank_lists(get_cos, pool=40):
        return [{bid: k + 1 for k, bid in enumerate(sorted(ids, key=lambda b: -get_cos(b, i))[:pool])}
                for i in range(len(cases))]

    lb, lt = rank_lists(cos_body), rank_lists(cos_title)

    def rrf_ranks(lb, lt, wc, K=10):
        out = []
        for i, c in enumerate(cases):
            sc = {}
            for bid, p in lb[i].items():
                sc[bid] = sc.get(bid, 0) + 1.0 / (K + p)
            for bid, p in lt[i].items():
                sc[bid] = sc.get(bid, 0) + wc / (K + p)
            out.append(rank_of(c['targets'], sorted(sc, key=lambda b: -sc[b])))
        return out

    for wc in (0.5, 1.0, 1.5):
        add('⑦ 方案2 RRF A+C(w=%.1f)' % wc, rrf_ranks(lb, lt, wc))

    # ===== 扰动检查：真实口语提问上，多一路标题会不会把现状搞乱 =====
    # 没有 ground truth，所以用"现状 A 路 top1"当参照：
    #   融合后的 top1 是否仍是它、它是否还在 top5。
    print('\n===== 扰动检查（真实提问抽样，参照 = 现状 A 路 top1）=====', flush=True)
    con = sqlite3.connect('file:%s?mode=ro' % args.db, uri=True)
    real, seen = [], set()
    for (q,) in con.execute('select question from qa_raw'):
        q = (q or '').strip()
        if q in seen or not (4 <= len(q) <= 40):
            continue
        if not any('\u4e00' <= ch <= '\u9fff' for ch in q):
            continue
        seen.add(q)
        real.append(q)
    con.close()
    random.Random(7).shuffle(real)
    real = real[:120]
    if len(cache.get('oral', [])) != len(real):
        cache['oral'] = embed_all(key, real, 'oral')
        with open(CACHE, 'wb') as f:
            pickle.dump(cache, f)

    agree = keep1 = keep5 = 0
    body_conf = 0.0
    for i, qv in enumerate(cache['oral']):
        qvn = norm(qv)
        ob = sorted(ids, key=lambda b: -cosine(qv, vbody[b], qvn, bo_norm[b]))
        ot = sorted(ids, key=lambda b: -cosine(qv, vtitle[b], qvn, ti_norm[b]))
        body_conf += cosine(qv, vbody[ob[0]], qvn, bo_norm[ob[0]])
        if ot[0] == ob[0]:
            agree += 1
        sc = {}
        for k, b in enumerate(ob[:40]):
            sc[b] = sc.get(b, 0) + 1.0 / (10 + k + 1)
        for k, b in enumerate(ot[:40]):
            sc[b] = sc.get(b, 0) + 1.0 / (10 + k + 1)
        fused = sorted(sc, key=lambda b: -sc[b])
        if fused[0] == ob[0]:
            keep1 += 1
        if ob[0] in fused[:5]:
            keep5 += 1
    n = len(real)
    print('  口语查询 %d 条；现状 A 路 top1 平均余弦 %.3f' % (n, body_conf / n), flush=True)
    print('  标题路 top1 与正文路 top1 相同的比例：%.1f%%' % (100.0 * agree / n), flush=True)
    print('  加标题路（RRF w=1.0）后，融合 top1 仍是正文 top1 的比例：%.1f%%' % (100.0 * keep1 / n), flush=True)
    print('  正文 top1 仍留在融合 top5 的比例：%.1f%%' % (100.0 * keep5 / n), flush=True)
    results['oral'] = {'n': n, 'body_top1_avg_cos': body_conf / n,
                       'agree_pct': 100.0 * agree / n,
                       'keep1_pct': 100.0 * keep1 / n, 'keep5_pct': 100.0 * keep5 / n}

    # ===== ⑨ 闸门阈值扫描：标题路什么时候该说话 =====
    # 上面的扰动检查说明：标题路当"平等的一路"会把口语提问带偏（RRF w=1.0 时
    # 一半的 top1 被换掉）。所以它应该只在"用户就是在问这个名字"时说话。
    # 这里扫一遍阈值 θ（标题余弦），看两边的触发率各是多少。
    ob_top = {i: max((cosine(qs[i], vtitle[b], qn[i], ti_norm[b]), b) for b in ids) for i in range(len(cases))}
    otop_hit = {i: cosine(qs[i], vtitle[next(iter(c['targets']))], qn[i], ti_norm[next(iter(c['targets']))])
                for i, c in enumerate(cases)}
    oral_top = []
    for i, qv in enumerate(cache['oral']):
        qvn = norm(qv)
        oral_top.append(max(cosine(qv, vtitle[b], qvn, ti_norm[b]) for b in ids))

    print('\n===== ⑨ 闸门阈值扫描（标题余弦 θ）=====', flush=True)
    print('  θ      名字查询：本体命中率  口语查询：触发率（=名字相似度≥θ）', flush=True)
    for th in (0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80):
        hit = sum(1 for i in range(len(cases)) if otop_hit[i] >= th) / len(cases)
        trig = sum(1 for c in oral_top if c >= th) / len(oral_top)
        print('  %.2f   %6.1f%%            %6.1f%%' % (th, hit * 100, trig * 100), flush=True)

    # 生产形态：闸门 + 钉顶（命中就把它排第一，其余按正文路排）
    print('\n  闸门+钉顶（θ 命中则该块置顶，其余按正文路顺序）：', flush=True)
    for th in (0.60, 0.65, 0.70, 0.75):
        ranks = []
        for i, c in enumerate(cases):
            best, bid = ob_top[i]
            rest = sorted(ids, key=lambda b: -cos_body(b, i))
            order = ([bid] + [b for b in rest if b != bid]) if best >= th else rest
            ranks.append(rank_of(c['targets'], order))
        m = metrics(ranks)
        add('⑨ 闸门钉顶 θ=%.2f' % th, ranks)
        # 口语侧扰动：这些查询会被闸门置顶（top1 一定被换掉）
        flips = sum(1 for c in oral_top if c >= th)
        print('        （口语侧触发 %d/%d = %.1f%%，这些会被置顶）'
              % (flips, len(oral_top), 100.0 * flips / len(oral_top)), flush=True)

    # 真实案例：什么是酸蚀之咬
    print('\n===== 案例：什么是酸蚀之咬 =====', flush=True)
    q = embed_batch(key, ['什么是酸蚀之咬'])[0]
    q_n = norm(q)
    for label, vecs, norms in (('body', vbody, bo_norm), ('title', vtitle, ti_norm), ('title+body', vtb, tb_norm)):
        scored = sorted(((cosine(q, vecs[i], q_n, norms[i]), i) for i in ids), reverse=True)
        top = scored[:5]
        titles = {b['id']: b['title'] for b in blocks}
        pos = next((k for k, (c, i) in enumerate(scored, 1) if i == 'acid-bite'), None)
        cos_ab = next((c for c, i in scored if i == 'acid-bite'), float('nan'))
        print('  [%s] acid-bite 第 %s 名（%.4f）；top3：%s' % (
            label, pos, cos_ab,
            ' > '.join('%.3f %s' % (c, titles[i]) for c, i in top)), flush=True)

    out = '/tmp/kb-title-embedding-result.json'
    with open(out, 'w', encoding='utf-8') as f:
        json.dump({'n': len(blocks), 'cases': len(cases), 'results': results}, f,
                  ensure_ascii=False, indent=2)
    print('\n结果已存 %s' % out, flush=True)


main()
