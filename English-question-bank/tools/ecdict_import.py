#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
ECDICT 词典数据 -> MySQL `word` 表 导入脚本

依赖:
    pip install pymysql

用法:
    # 0) 只检查数据库表结构（秒级，先确认 ALTER 有没有执行）
    python tools/ecdict_import.py --check-db

    # 1) 先空跑, 看筛选出多少词条、释义质量如何, 不写库也不需要密码
    python tools/ecdict_import.py --csv ecdict.csv --dry-run

    # 2) 确认没问题后真导入（会提示输入数据库密码）
    python tools/ecdict_import.py --csv ecdict.csv

    # 调整范围: 放宽到词频前 5 万, 并保留 [网络]/[计] 等领域释义
    python tools/ecdict_import.py --csv ecdict.csv --freq-limit 50000 --keep-domains

设计要点（对应 ECDICT 的实测特性）:
  1. 必须用 csv 模块解析。ECDICT 采用"最小化引号", 字段内含逗号和多义项分隔符,
     手工 split(",") 必定错位。
  2. 多义项分隔符在文件里是字面的反斜杠 n（不是真换行）。脚本对两种情况都兼容,
     并会统计实际检测到的形态。
  3. 只保留"单个英文单词", 过滤掉 "why not...?" / "no fonts installed" 这类句子。
  4. 按考试大纲标签(tag) + 词频(frq/bnc) 双重条件筛选, 默认约 3~6 万常用词。
  5. part_of_speech 从中文释义前缀提取 —— ECDICT 官方 pos 字段大面积为空, 不可靠。
  6. ECDICT 只有一套音标(偏英式), 因此只写 phonetic_uk, phonetic_us 留空。
"""

import argparse
import csv
import getpass
import os
import re
import sys
from collections import Counter

# 脚本自身所在目录。默认词典路径基于它解析，这样无论从哪个目录调用，
# 都能定位到 tools/ecdict.csv —— 不受"当前工作目录"影响。
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

# Windows 控制台默认 GBK：中文会乱码，✗ 这类符号编不了会变成 \u2717 转义。
# stdout 和 stderr 都必须改 —— SystemExit 的内容是走 stderr 的。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass  # 老版本 Python 没有 reconfigure, 忽略即可


# ===========================================================================
#  可调参数
# ===========================================================================

# 考试大纲标签。ECDICT 的 tag 字段由这些 token 用空格分隔组成。
EXAM_TAGS = {"zk", "gk", "cet4", "cet6", "ky", "toefl", "ielts", "gre"}

# 只保留单个英文单词（允许连字符与撇号），过滤短语与整句。
WORD_RE = re.compile(r"^[A-Za-z][A-Za-z'\-]{0,63}$")

# 从中文释义开头提取词性：  "n. 罩；风帽" -> n.
POS_RE = re.compile(
    r"^\s*(n|v|vt|vi|adj|adv|ad|a|prep|conj|pron|num|art|int|aux|abbr)\s*\."
)

# 词性写法归一化
POS_NORMALIZE = {
    "a": "adj", "adj": "adj",
    "ad": "adv", "adv": "adv",
}

# 领域标记开头的释义，如 [网络] 胡德 / [计] 与 / art. [计] 累加器 ——
# 对英语学习者基本是噪音（实测 "a" 的释义里混了一长串计算机术语）。
# 正则允许前面带一个可选词性前缀：  "art. [计] ..." 也能被识别。
DOMAIN_SENSE_RE = re.compile(r"^(?:[a-z]{1,5}\.\s*)?\[[^\]]{1,8}\]")

BATCH_SIZE = 2000

# MySQL 8.0.19+ 的行别名写法，替代已废弃的 VALUES() 函数
INSERT_SQL = """
INSERT INTO word
  (headword, display_form, phonetic_uk, part_of_speech, translation,
   exchange, tag, bnc, frq, lookup_status, source)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'LOCAL', 'ecdict') AS new
ON DUPLICATE KEY UPDATE
  display_form   = new.display_form,
  phonetic_uk    = new.phonetic_uk,
  part_of_speech = new.part_of_speech,
  translation    = new.translation,
  exchange       = new.exchange,
  tag            = new.tag,
  bnc            = new.bnc,
  frq            = new.frq
"""


# ===========================================================================
#  工具函数
# ===========================================================================

def to_rank(value):
    """ECDICT 用 0 表示"未进词频榜"，统一转成 None。"""
    try:
        n = int(value)
        return n if n > 0 else None
    except (TypeError, ValueError):
        return None


def truncate_bytes(text, limit=60000):
    """TEXT 列上限 65535 字节，按字节截断且不切碎多字节字符。"""
    raw = text.encode("utf-8")
    if len(raw) <= limit:
        return text
    return raw[:limit].decode("utf-8", errors="ignore")


def split_senses(raw):
    """
    把释义字段拆成多条。

    兼容两种形态：
      a) 文件里是字面反斜杠+n          -> replace 成真换行再切
      b) 文件里是真实换行(被 csv 读入)  -> 直接切
    """
    if not raw:
        return []
    # ECDICT 的转义形态不统一：实测算遇到字面 \r（如 "第一的\r"），
    # 因此 \r\n / \r / \n 三种都要处理，真实换行也要兼容。
    text = raw.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n")
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    return [line.strip() for line in text.split("\n") if line.strip()]


def extract_pos(senses):
    """从释义前缀提取词性，最多取前 3 个不重复项。"""
    found = []
    for sense in senses:
        m = POS_RE.match(sense)
        if not m:
            continue
        pos = POS_NORMALIZE.get(m.group(1).lower(), m.group(1).lower())
        if pos not in found:
            found.append(pos)
    return "/".join(found[:3])[:32] or None


def build_translation(senses, keep_domains):
    """
    把多义项拼成一行（分号分隔）。

    默认丢弃 [网络]/[计]/[医] 这类领域标记开头的义项 ——
    实测 "a" 的释义是 "第一个字母 A; 一个; 第一的; art. [计] 累加器, 加法器,
    地址, 振幅, 模拟, 区域, 面积, 汇编, 组件, 异步"，后半段对学习者毫无价值。

    全部义项都被丢弃时退回原文，避免整条词条变成空释义。
    """
    kept = senses
    if not keep_domains:
        filtered = [s for s in senses if not DOMAIN_SENSE_RE.match(s)]
        kept = filtered or senses
    if not kept:
        return None
    return truncate_bytes("; ".join(kept))


# ===========================================================================
#  主流程
# ===========================================================================

def parse_args():
    p = argparse.ArgumentParser(
        description="将 ECDICT 词典数据导入 MySQL 的 word 表",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--csv", default=os.path.join(SCRIPT_DIR, "ecdict.csv"),
                   help="ECDICT 的 csv 路径（默认取脚本同目录下的 ecdict.csv）")
    p.add_argument("--host", default="localhost")
    p.add_argument("--port", type=int, default=3306)
    p.add_argument("--user", default="root")
    p.add_argument("--password", default=None, help="不传则交互式输入")
    p.add_argument("--database", default="english_question_bank")
    p.add_argument("--freq-limit", type=int, default=30000,
                   help="词频序号小于该值的词一并收录（默认 30000）")
    p.add_argument("--keep-domains", action="store_true",
                   help="保留 [网络]/[计]/[医] 等领域标记开头的释义（默认丢弃）")
    p.add_argument("--dry-run", action="store_true",
                   help="只统计和抽样展示，不写数据库")
    p.add_argument("--check-db", action="store_true",
                   help="只连接数据库检查表结构，然后退出（不读 csv、不导入）")
    return p.parse_args()


def read_and_filter(path, freq_limit, keep_domains):
    stats = Counter()
    selected = {}          # headword(小写) -> 记录 dict
    header = None
    raw_newline_in_field = 0
    literal_escape_in_field = 0

    if not os.path.isfile(path):
        raise SystemExit(
            f"\n✗ 找不到词典文件: {path}\n"
            f"  当前工作目录: {os.getcwd()}\n"
            "  提示: 用 --csv 指定完整路径可以避免相对路径问题，例如\n"
            r"        --csv E:\github\online-English-question-bank\English-question-bank\tools\ecdict.csv"
        )

    with open(path, "r", encoding="utf-8", newline="") as fh:
        reader = csv.reader(fh)
        try:
            header = next(reader)
        except StopIteration:
            raise SystemExit(f"文件是空的: {path}")

        expected = len(header)
        print(f"表头({expected} 列): {','.join(header)}")
        print("开始扫描 ...")

        for lineno, row in enumerate(reader, start=2):
            stats["总行数"] += 1

            # 77 万行要扫描一分多钟，必须给进度，否则看起来像卡死
            if stats["总行数"] % 100000 == 0:
                print(f"  ...已扫描 {stats['总行数']:,} 行", end="\r", flush=True)

            # --- 结构性校验：字段数不对说明 CSV 解析出了问题 ---
            if len(row) != expected:
                stats["字段数异常(已跳过)"] += 1
                if stats["字段数异常(已跳过)"] <= 3:
                    print(f"  ⚠ 第 {lineno} 行有 {len(row)} 个字段，期望 {expected}: {row[:3]}")
                continue

            rec = dict(zip(header, row))

            # --- 检测多义项分隔符的真实形态 ---
            trans_raw = rec.get("translation") or ""
            if "\n" in trans_raw:
                raw_newline_in_field += 1
            if "\\n" in trans_raw:
                literal_escape_in_field += 1

            word = (rec.get("word") or "").strip()
            if not word:
                stats["空词条"] += 1
                continue
            if not WORD_RE.match(word):
                stats["非单词(短语/句子)"] += 1
                continue

            tags = (rec.get("tag") or "").split()
            frq = to_rank(rec.get("frq"))
            bnc = to_rank(rec.get("bnc"))

            hit_tag = bool(EXAM_TAGS.intersection(tags))
            hit_freq = (
                (frq is not None and frq <= freq_limit)
                or (bnc is not None and bnc <= freq_limit)
            )
            if not (hit_tag or hit_freq):
                stats["未达筛选门槛"] += 1
                continue

            senses = split_senses(trans_raw)
            if not senses:
                stats["无中文释义"] += 1
                continue

            key = word.lower()
            rank = frq if frq is not None else 10 ** 9

            prev = selected.get(key)
            if prev is not None:
                stats["重复词条(已去重)"] += 1
                if prev["_rank"] <= rank:
                    continue       # 保留词频更靠前的那条

            selected[key] = {
                "headword": key,
                "display_form": word,
                "phonetic_uk": (rec.get("phonetic") or "").strip()[:64] or None,
                "part_of_speech": extract_pos(senses),
                "translation": build_translation(senses, keep_domains),
                "exchange": (rec.get("exchange") or "").strip()[:255] or None,
                "tag": " ".join(tags)[:64] or None,
                "bnc": bnc,
                "frq": frq,
                "_rank": rank,
            }
            stats["已选中"] += 1

    if stats["总行数"] >= 100000:
        print(" " * 40, end="\r")   # 擦掉进度行

    if raw_newline_in_field == 0 and literal_escape_in_field > 0:
        print(f"检测到多义项分隔符为【字面 \\n 转义】（{literal_escape_in_field} 行）")
    elif literal_escape_in_field == 0 and raw_newline_in_field > 0:
        print(f"检测到多义项分隔符为【真实换行】（{raw_newline_in_field} 行）")
    elif literal_escape_in_field and raw_newline_in_field:
        print(f"检测到两种分隔符混合：字面转义 {literal_escape_in_field} 行 / 真实换行 {raw_newline_in_field} 行")

    return stats, selected


def show_stats(stats, selected):
    print("\n" + "=" * 56)
    print("扫描统计")
    print("=" * 56)
    for k, v in stats.most_common():
        print(f"  {k:<24} {v:>8}")
    print("-" * 56)
    print(f"  {'最终入库词条数':<24} {len(selected):>8}")

    print("\n" + "=" * 56)
    print("抽样预览（前 8 条，按词频排序）")
    print("=" * 56)
    sample = sorted(selected.values(), key=lambda r: r["_rank"])[:8]
    for r in sample:
        print(f"\n  {r['display_form']}  [{r['phonetic_uk'] or '-'}]  ({r['part_of_speech'] or '-'})")
        print(f"    frq={r['frq']}  bnc={r['bnc']}  tag={r['tag']}")
        print(f"    释义: {(r['translation'] or '')[:110]}")


REQUIRED_COLUMNS = [
    "headword", "display_form", "phonetic_uk", "part_of_speech",
    "translation", "exchange", "tag", "bnc", "frq",
    "lookup_status", "source",
]


def connect_db(args):
    try:
        import pymysql
    except ImportError:
        raise SystemExit("\n缺少 pymysql，请先执行:\n    pip install pymysql")

    password = args.password
    if password is None:
        password = getpass.getpass(f"请输入 {args.user} 的 MySQL 密码: ")

    try:
        return pymysql.connect(
            host=args.host, port=args.port, user=args.user,
            password=password, database=args.database,
            charset="utf8mb4", autocommit=False,
        )
    except pymysql.err.OperationalError as e:
        code = e.args[0] if e.args else None
        hint = {
            1045: "密码不对，或该账号不允许从 localhost 登录",
            1049: f"数据库 {args.database} 不存在，确认与 Navicat 里的库名一致",
            2003: f"连不上 {args.host}:{args.port}，确认 Windows 服务 MySQL80 正在运行",
        }.get(code, "请检查上面的连接参数")
        raise SystemExit(f"\n✗ 连接数据库失败\n  错误: {e}\n  提示: {hint}")


def check_schema(conn, database):
    """
    导入前检查 word 表结构。

    存在意义：如果 alter-01-word-dict-fields.sql 没执行，导入会在写第一批数据时
    才报 Unknown column。csv 有 77 万行，读一遍要一两分钟 —— 提前检查能省掉这次白跑。
    """
    with conn.cursor() as cur:
        cur.execute(
            "SELECT column_name, column_type FROM information_schema.columns "
            "WHERE table_schema = %s AND table_name = 'word' "
            "ORDER BY ordinal_position",
            (database,),
        )
        cols = cur.fetchall()

    if not cols:
        raise SystemExit(
            f"\n✗ 库 {database} 里没有 word 表。\n"
            "  请先执行 src/main/resources/db/schema.sql 建表。"
        )

    have = {c[0].lower() for c in cols}
    missing = [c for c in REQUIRED_COLUMNS if c not in have]
    if missing:
        raise SystemExit(
            "\n✗ word 表缺少以下列: " + ", ".join(missing) + "\n"
            "  说明 alter-01-word-dict-fields.sql 还没执行。\n"
            "  请在 Navicat 里先执行 src/main/resources/db/alter-01-word-dict-fields.sql"
        )

    print(f"✓ 表结构检查通过，word 表共 {len(cols)} 列:")
    for cname, ctype in cols:
        print(f"    {cname:<18} {ctype}")


def main():
    args = parse_args()

    print("=" * 56)
    print("ECDICT 导入工具")
    print("=" * 56)
    print(f"数据文件   : {args.csv}")
    print(f"目标数据库 : {args.user}@{args.host}:{args.port}/{args.database}")
    print(f"词频门槛   : <= {args.freq_limit}")
    print(f"保留领域释义: {args.keep_domains}")
    print(f"模式       : {'检查数据库' if args.check_db else ('空跑(dry-run)，不写库' if args.dry_run else '正式导入')}")
    print()

    # --check-db: 只验证表结构，不读 csv（秒级返回）
    if args.check_db:
        conn = connect_db(args)
        try:
            check_schema(conn, args.database)
        finally:
            conn.close()
        print("\n数据库这边没问题，可以开始导入了。")
        return

    # 正式导入时先连库检查表结构。
    # 顺序很重要：csv 有 77 万行，读一遍要一两分钟，等读完才发现列缺失就白等了。
    conn = None
    if not args.dry_run:
        conn = connect_db(args)
        check_schema(conn, args.database)
        print()

    stats, selected = read_and_filter(args.csv, args.freq_limit, args.keep_domains)
    show_stats(stats, selected)

    if not selected:
        if conn:
            conn.close()
        raise SystemExit("\n没有选出任何词条，请检查筛选条件或 csv 文件。")

    if args.dry_run:
        print("\n空跑结束，未写入数据库。去掉 --dry-run 即可正式导入。")
        return

    rows = [
        (r["headword"], r["display_form"], r["phonetic_uk"], r["part_of_speech"],
         r["translation"], r["exchange"], r["tag"], r["bnc"], r["frq"])
        for r in selected.values()
    ]

    print(f"\n开始写入 {len(rows)} 条 ...")
    written = 0
    try:
        with conn.cursor() as cur:
            for i in range(0, len(rows), BATCH_SIZE):
                batch = rows[i:i + BATCH_SIZE]
                cur.executemany(INSERT_SQL, batch)
                conn.commit()
                written += len(batch)
                print(f"  已写入 {written}/{len(rows)}", end="\r")
        print()
    finally:
        conn.close()

    print(f"\n导入完成，共写入 {written} 条。")
    print("验证 SQL:")
    print("  SELECT COUNT(*) FROM word;")
    print("  SELECT headword, phonetic_uk, part_of_speech, translation")
    print("    FROM word ORDER BY frq LIMIT 10;")


if __name__ == "__main__":
    main()
