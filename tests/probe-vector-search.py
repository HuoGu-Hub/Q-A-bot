"""一次性探针：验证「向量路」对口语化中文提问的检索效果。

用法: python3 tests/probe-vector-search.py "问题1" "问题2" ...
不传参数则跑内置样例。仅用于评估，不参与线上逻辑。
"""
import array
import json
import math
import struct
import sys
import urllib.request
from operator import mul

ROOT = '/app/workspace/qqbot/server/data/kb'
ENV = '/app/workspace/qqbot/.env'
MODEL = 'BAAI/bge-m3'
URL = 'https://api.siliconflow.cn/v1/embeddings'


def api_key():
    with open(ENV, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line.startswith('SILICONFLOW_API_KEY='):
                return line.split('=', 1)[1].strip().strip('"').strip("'")
    raise SystemExit('没有找到 SILICONFLOW_API_KEY')


def load_index():
    raw = open(ROOT + '/index.bin', 'rb').read()
    n, d = struct.unpack('>ii', raw[4:12])
    arr = array.array('f')
    arr.frombytes(raw[12:12 + n * d * 4])
    if sys.byteorder == 'little':
        arr.byteswap()
    norms = []
    for i in range(n):
        row = arr[i * d:(i + 1) * d]
        norms.append(math.sqrt(sum(map(mul, row, row))) or 1.0)
    titles = []
    for line in open(ROOT + '/chunks.jsonl', encoding='utf-8'):
        if line.strip():
            titles.append(json.loads(line)['title'])
    return arr, norms, titles, n, d


def embed(key, q):
    body = json.dumps({'model': MODEL, 'input': [q], 'encoding_format': 'float'}).encode()
    req = urllib.request.Request(URL, data=body, headers={
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + key,
    })
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)['data'][0]['embedding']


def main():
    key = api_key()
    arr, norms, titles, n, d = load_index()
    print('索引: %d 块 x %d 维' % (n, d))
    queries = sys.argv[1:] or ['木头', '等级上限', '余烬碎片', '灵火祭坛怎么升级',
                               '怎么驯服卷毛山羊', 'alte', '怎么种地']
    for q in queries:
        v = embed(key, q)
        nv = math.sqrt(sum(map(mul, v, v))) or 1.0
        out = []
        for i in range(n):
            row = arr[i * d:(i + 1) * d]
            out.append(sum(map(mul, v, row)) / (nv * norms[i]))
        order = sorted(range(n), key=lambda i: -out[i])[:5]
        print('--- %s' % q)
        for i in order:
            print('    %.3f  %s' % (out[i], titles[i]))


main()
