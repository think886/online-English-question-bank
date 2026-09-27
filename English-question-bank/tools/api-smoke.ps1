# API 冒烟测试 —— 覆盖「开始练习 → 划词标记 → 作答 → 交卷 → 结果页」完整链路
#
# 用法（应用需已在 18080 端口运行）：
#   powershell -ExecutionPolicy Bypass -File tools\api-smoke.ps1
#
# ⚠ 两条硬性约定，破坏任何一条都会让结果不可信：
#
#   1. 本文件必须保存为「UTF-8 with BOM」。Windows PowerShell 5.1 读无 BOM 的 .ps1
#      会按 GBK 解码，中文字符串里的引号被吃掉，直接报 ParserError。
#
#   2. 测试数据必须挂在【本脚本自己创建的用户】上，并在结束时删干净。
#      最早这脚本用的是演示用户 demo(id=9)，结果它标记生词、提交错题后，
#      WordMarkVerifier 的「生词本 mark_count==1」和 AnswerVerifier 的
#      「错题本 2 条」就都失败了 —— 那两个自检当时只断言全局绝对值，
#      看到别人写进来的数据就误报。现在两边都改成了断言自己造成的增量，
#      但脚本仍然用独立用户：测完即删，不污染任何演示数据。
#
# 为什么不写成单元测试：这些接口的价值在于「分层是否真的接通了」——
# Service 层的 47 项自检已经证明业务逻辑正确，这里要证明的是
# Controller / JSON 序列化 / 异常映射 / HTTP 状态码这一层也对。

$ErrorActionPreference = 'Continue'
$Base = 'http://127.0.0.1:18080'
$MysqlExe = 'D:\MySQL\MySQL Server 8.0\bin\mysql.exe'
$MysqlArgs = @('-uroot', '-p123456', '--default-character-set=utf8mb4', 'english_question_bank')
$script:Pass = 0
$script:Fail = 0

# ---------------------------------------------------------------------------
# 测试用户：直接写库创建（本项目还没有注册接口），结束时连同其数据一起删除
# ---------------------------------------------------------------------------

function New-SmokeUser {
    param([string]$Name)
    $sql = "INSERT INTO sys_user (username, nickname) VALUES ('$Name', 'API 冒烟测试'); SELECT LAST_INSERT_ID();"
    $out = & $MysqlExe @MysqlArgs -N -B -e $sql 2>$null
    $id = $out | Where-Object { $_ -match '^\d+$' } | Select-Object -Last 1
    if (-not $id) { throw "创建测试用户失败：$Name" }
    return [int]$id
}

function Remove-SmokeUser {
    param([int]$Id)
    # ⚠ 顺序不能反：fk_sess_user / fk_mark_user / fk_vocab_user 都是【不带
    #   ON DELETE CASCADE】的普通外键，直接删 sys_user 会被子表挡住。
    #   先删 practice_session（它会级联带走 session_question / answer_record /
    #   translation_grading / 该会话下的 user_word_mark），再删无会话的标记与生词本。
    $sql = @"
DELETE FROM practice_session WHERE user_id = $Id;
DELETE FROM user_word_mark   WHERE user_id = $Id;
DELETE FROM user_vocabulary  WHERE user_id = $Id;
DELETE FROM sys_user         WHERE id = $Id;
"@
    & $MysqlExe @MysqlArgs -e $sql 2>$null | Out-Null
}

$stamp = [DateTime]::Now.ToString('HHmmss')
$script:Uid = New-SmokeUser "api_smoke_$stamp"
$script:OtherUid = New-SmokeUser "api_smoke_other_$stamp"
Write-Host "测试用户：uid=$script:Uid，越权对照 uid=$script:OtherUid" -ForegroundColor DarkGray

# ---------------------------------------------------------------------------

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [int]$UserId = $script:Uid     # 0 表示不带身份头
    )
    $headers = @{}
    if ($UserId -gt 0) { $headers['X-Debug-User-Id'] = "$UserId" }

    try {
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Depth 10 -Compress
            $bytes = [Text.Encoding]::UTF8.GetBytes($json)
            $r = Invoke-WebRequest -Method $Method -Uri "$Base$Path" -Headers $headers `
                    -ContentType 'application/json; charset=utf-8' -Body $bytes -UseBasicParsing
        }
        else {
            $r = Invoke-WebRequest -Method $Method -Uri "$Base$Path" -Headers $headers -UseBasicParsing
        }
        return [pscustomobject]@{
            Code = [int]$r.StatusCode
            Body = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
        }
    }
    catch {
        # PowerShell 5.1 没有 -SkipHttpErrorCheck，非 2xx 一律走异常分支
        $resp = $_.Exception.Response
        if ($null -eq $resp) { return [pscustomobject]@{ Code = -1; Body = $_.Exception.Message } }
        $sr = New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
        $text = $sr.ReadToEnd(); $sr.Close()
        return [pscustomobject]@{ Code = [int]$resp.StatusCode; Body = $text }
    }
}

function Check {
    param([string]$Name, [bool]$Ok, [string]$Detail = '')
    if ($Ok) {
        $script:Pass++
        Write-Host ("  [PASS] {0}  {1}" -f $Name, $Detail) -ForegroundColor Green
    }
    else {
        $script:Fail++
        Write-Host ("  [FAIL] {0}  {1}" -f $Name, $Detail) -ForegroundColor Red
    }
}

# 响应体里是否出现了「交卷前绝不能露」的字段
function Test-Leaked {
    param([string]$Body)
    return $Body -match '"isCorrect"|"referenceAnswer"|"analysis"|"correctAnswer"|"passwordHash"'
}

# ⚠ PowerShell 5.1 的坑：(单个 PSCustomObject).Count 返回【空】而不是 1，
#   所以凡是要数「符合条件的条数」的地方，一律用 @(...) 强制成数组再取 .Count。
function Count-Of {
    param($Items)
    return @($Items).Count
}

$sid = $null
$tsid = $null

try {
    Write-Host "`n========== 一、阅读题链路 ==========" -ForegroundColor Cyan

    # ---------- 1. 开始练习 ----------
    $r = Invoke-Api POST '/api/practices/reading' @{ passageId = 1 }
    Check '开始阅读练习返回 201' ($r.Code -eq 201) "HTTP $($r.Code)"
    $start = $r.Body | ConvertFrom-Json
    $sid = $start.data.sessionId
    $q1 = $start.data.questions[0].id
    $q2 = $start.data.questions[1].id
    Check '返回 2 道题' ((Count-Of $start.data.questions) -eq 2) "sessionId=$sid q1=$q1 q2=$q2"
    Check '开始练习响应不含正确答案 / 解析 / 译文' (-not (Test-Leaked $r.Body))

    # ---------- 2. 划词标记 ----------
    $markBody = @{
        questionId = $null; sourceField = 'PASSAGE'; surfaceForm = 'running'
        sentence = 'He is running fast.'; charStart = 10; charEnd = 17
    }
    $r = Invoke-Api POST "/api/practices/$sid/marks" $markBody
    $m = $r.Body | ConvertFrom-Json
    Check '标记文章正文中的词返回 200' ($r.Code -eq 200) "HTTP $($r.Code)"
    Check '词形归一化 running → run' ($m.data.normalizedForm -eq 'run') "normalized=$($m.data.normalizedForm)"
    Check '返回中文释义' ($null -ne $m.data.translation) "translation=$($m.data.translation)"
    Check '首次标记 alreadyMarked=false' ($m.data.alreadyMarked -eq $false)

    $r = Invoke-Api POST "/api/practices/$sid/marks" $markBody
    $m2 = $r.Body | ConvertFrom-Json
    Check '同位置重复标记是空操作' ($m2.data.alreadyMarked -eq $true) "markId 相同=$($m2.data.markId -eq $m.data.markId)"

    # 同一词换位置 → 新增标记行，但结果页去重后仍算一个词
    $r = Invoke-Api POST "/api/practices/$sid/marks" @{
        questionId = $null; sourceField = 'PASSAGE'; surfaceForm = 'Running,'
        sentence = 'Running is good.'; charStart = 100; charEnd = 108
    }
    $m3 = $r.Body | ConvertFrom-Json
    Check '同词不同位置：新增标记行' ($m3.data.alreadyMarked -eq $false) "normalizedForm=$($m3.data.normalizedForm)"

    # 在题目题干上划词（带 questionId）
    $r = Invoke-Api POST "/api/practices/$sid/marks" @{
        questionId = $q1; sourceField = 'STEM'; surfaceForm = 'harvest'
        sentence = 'They harvest vegetables.'; charStart = 5; charEnd = 12
    }
    $m4 = $r.Body | ConvertFrom-Json
    Check '在题干上划词返回 200' ($r.Code -eq 200) "wordId=$($m4.data.wordId)"

    $r = Invoke-Api GET "/api/practices/$sid/marks"
    $list = $r.Body | ConvertFrom-Json
    Check '标记列表返回 3 行（未去重）' ((Count-Of $list.data) -eq 3) "count=$(Count-Of $list.data)"
    Check '标记列表按 charStart 升序' ($list.data[0].charStart -eq 5) "首个 charStart=$($list.data[0].charStart)"
    # 偏移量必须带锚点，否则前端不知道高亮画在哪段文本上（曾漏掉，第 22 次修订补上）
    Check '标记列表带 questionId 锚点' ($list.data[0].questionId -eq $q1) "questionId=$($list.data[0].questionId) 期望 $q1"
    Check '标记列表带 sourceField 锚点' ($list.data[0].sourceField -eq 'STEM') "sourceField=$($list.data[0].sourceField)"
    Check '文章正文上的标记 questionId 为 null' ($null -eq $list.data[1].questionId) "sourceField=$($list.data[1].sourceField)"

    # 校验：不合法 sourceField 被拦
    $r = Invoke-Api POST "/api/practices/$sid/marks" @{
        questionId = $null; sourceField = 'HACK'; surfaceForm = 'x'; charStart = 0; charEnd = 1
    }
    Check '非法 sourceField 返回 400' ($r.Code -eq 400) "message=$(($r.Body | ConvertFrom-Json).message)"

    # ---------- 3. 作答 ----------
    $r = Invoke-Api POST "/api/practices/$sid/answers" @{ questionId = $q1; userAnswer = 'A' }
    $ans1 = $r.Body | ConvertFrom-Json
    Check '提交第 1 题返回 200' ($r.Code -eq 200) "isCorrect=$($ans1.data.isCorrect)"

    $r = Invoke-Api POST "/api/practices/$sid/answers" @{ questionId = $q2; userAnswer = 'B' }
    $ans2 = $r.Body | ConvertFrom-Json
    Check '提交第 2 题返回 200' ($r.Code -eq 200) "isCorrect=$($ans2.data.isCorrect)"

    $r = Invoke-Api POST "/api/practices/$sid/answers" @{ questionId = $q2; userAnswer = 'E' }
    Check '非法选项标识返回 400' ($r.Code -eq 400) "message=$(($r.Body | ConvertFrom-Json).message)"

    # ---------- 4. 交卷前不能看结果 ----------
    $r = Invoke-Api GET "/api/practices/$sid/result"
    Check '未交卷时结果页返回 409' ($r.Code -eq 409) "message=$(($r.Body | ConvertFrom-Json).message)"

    # ---------- 5. 交卷 ----------
    $r = Invoke-Api POST "/api/practices/$sid/finish"
    $fin = $r.Body | ConvertFrom-Json
    Check '交卷返回 200' ($r.Code -eq 200) "HTTP $($r.Code)"
    Check '会话状态置为 FINISHED' ($fin.data.status -eq 'FINISHED') "status=$($fin.data.status)"
    Check '统计已重算（已答 2）' ($fin.data.answeredCount -eq 2) "answered=$($fin.data.answeredCount) correct=$($fin.data.correctCount) score=$($fin.data.score)"

    $r = Invoke-Api POST "/api/practices/$sid/finish"
    Check '重复交卷返回 409' ($r.Code -eq 409) "message=$(($r.Body | ConvertFrom-Json).message)"

    # ---------- 6. 结果页 ----------
    $r = Invoke-Api GET "/api/practices/$sid/result"
    $rawResult = $r.Body
    $res = $rawResult | ConvertFrom-Json
    Check '交卷后结果页返回 200' ($r.Code -eq 200) "HTTP $($r.Code)"
    Check '结果页含 2 道题的明细' ((Count-Of $res.data.answers) -eq 2) "count=$(Count-Of $res.data.answers)"
    Check '结果页含正确的选项标识' ($null -ne $res.data.answers[0].correctAnswer) "correctAnswer=$($res.data.answers[0].correctAnswer)"
    # 示例题库的 analysis 就是 NULL，所以只能断言「字段存在」（null 也说明字段被序列化了）
    Check '结果页单题含 analysis 字段' ($rawResult -match '"analysis"')

    # 判分自洽性：isCorrect 必须等于「用户答案 == 正确选项」
    $selfConsistent = $true
    foreach ($a in $res.data.answers) {
        $expect = ($a.userAnswer -eq $a.correctAnswer)
        if ($a.isCorrect -ne $expect) { $selfConsistent = $false }
    }
    Check '判分与正确答案自洽（isCorrect == userAnswer==correctAnswer）' $selfConsistent

    Check '结果页标记词已按原形去重（3 次标记 → 2 个词）' ((Count-Of $res.data.markedWords) -eq 2) "count=$(Count-Of $res.data.markedWords)"
    Check '去重标记词带释义' ($null -ne $res.data.markedWords[0].translation) "first=$($res.data.markedWords[0].normalizedForm) → $($res.data.markedWords[0].translation)"
    Check '去重标记词记录了标记次数' ($res.data.markedWords[0].markCount -ge 1) "markCount=$($res.data.markedWords[0].markCount)"
    Check 'run 的 markCount=2（同词标了两次）' ((($res.data.markedWords | Where-Object { $_.normalizedForm -eq 'run' }).markCount) -eq 2)
    Check '结果页返回了文章本体' ($null -ne $res.data.passage) "title=$($res.data.passage.title)"

    $withMarks = @($res.data.answers | Where-Object { (Count-Of $_.markedWords) -gt 0 })
    Check '结果页单题明细里带上了该题标记的词' ($withMarks.Count -eq 1) "带标记的题数=$($withMarks.Count)"
    Check '单题内的标记同样带锚点' ($withMarks[0].markedWords[0].questionId -eq $q1) "questionId=$($withMarks[0].markedWords[0].questionId)"

    # ---------- 7. 越权与身份 ----------
    $r = Invoke-Api GET "/api/practices/$sid/result" -UserId $script:OtherUid
    Check '别人的会话看结果被拒（当前 409，应改 403/404 → backlog B-12）' ($r.Code -eq 409) "HTTP $($r.Code)"

    $r = Invoke-Api POST "/api/practices/$sid/finish" -UserId $script:OtherUid
    Check '别人的会话交卷被拒' ($r.Code -ge 400) "HTTP $($r.Code)"

    $r = Invoke-Api GET "/api/practices/$sid/result" -UserId 0
    Check '不带身份头返回 401' ($r.Code -eq 401) "HTTP $($r.Code)"

    $r = Invoke-Api POST "/api/practices/999999/answers" @{ questionId = $q1; userAnswer = 'A' }
    Check '不存在的会话返回 4xx' ($r.Code -ge 400) "HTTP $($r.Code)"

    Write-Host "`n========== 二、翻译题链路 ==========" -ForegroundColor Cyan

    $r = Invoke-Api POST '/api/practices/translation' @{ count = 1 }
    $tstart = $r.Body | ConvertFrom-Json
    $tsid = $tstart.data.sessionId
    $tq = $tstart.data.questions[0].id
    Check '开始翻译练习返回 201' ($r.Code -eq 201) "sessionId=$tsid mode=$($tstart.data.mode)"
    Check '翻译题响应不含参考译文' (-not (Test-Leaked $r.Body))

    $r = Invoke-Api POST "/api/practices/$tsid/marks" @{
        questionId = $tq; sourceField = 'SOURCE_TEXT'; surfaceForm = 'abandoned'
        sentence = 'The plan was abandoned.'; charStart = 12; charEnd = 21
    }
    $tm = $r.Body | ConvertFrom-Json
    Check '在翻译原文上划词并归一化' ($tm.data.normalizedForm -eq 'abandon') "abandoned → $($tm.data.normalizedForm)"

    $r = Invoke-Api POST "/api/practices/$tsid/answers" @{
        questionId = $tq; userAnswer = '这个计划被放弃了。'
    }
    $tans = $r.Body | ConvertFrom-Json
    Check '提交译文返回 200 且状态为待评分' ($tans.data.gradingStatus -eq 'PENDING') "gradingStatus=$($tans.data.gradingStatus) isCorrect=$($tans.data.isCorrect)"

    $r = Invoke-Api POST "/api/practices/$tsid/finish"
    Check '翻译练习交卷返回 200' ($r.Code -eq 200) "HTTP $($r.Code)"

    $r = Invoke-Api GET "/api/practices/$tsid/result"
    $tres = $r.Body | ConvertFrom-Json
    Check '翻译结果页返回 200' ($r.Code -eq 200) "HTTP $($r.Code)"
    Check '翻译结果页 passage 为 null' ($null -eq $tres.data.passage)
    Check '翻译结果页含参考译文' ($null -ne $tres.data.answers[0].referenceAnswer) "referenceAnswer 长度=$(if ($tres.data.answers[0].referenceAnswer) { $tres.data.answers[0].referenceAnswer.Length } else { 0 })"
    Check '翻译题 correctAnswer 为 null（不是选择题）' ($null -eq $tres.data.answers[0].correctAnswer)
    Check '翻译结果页保留用户译文' ($tres.data.answers[0].userAnswer -eq '这个计划被放弃了。')
    Check '翻译结果页含标记词及释义' ((Count-Of $tres.data.markedWords) -eq 1) "first=$($tres.data.markedWords[0].normalizedForm)"
    # ⚠ 结果页【顶层】的 markedWords 是「按原形去重的汇总」(MarkedWordSummaryResponse)，
    #   它刻意不带 sourceField / questionId —— 同一个词可能在多处被标，锚点不唯一。
    #   带锚点的是【单题明细】里的那一份 markedWords。
    Check '翻译结果页单题明细里的标记带 sourceField 锚点' `
        ($tres.data.answers[0].markedWords[0].sourceField -eq 'SOURCE_TEXT') `
        "sourceField=$($tres.data.answers[0].markedWords[0].sourceField)"
}
finally {
    # 无论成败都要清理，否则残留数据会让 WordMarkVerifier / AnswerVerifier 误报
    Remove-SmokeUser $script:Uid
    Remove-SmokeUser $script:OtherUid

    $leftSessions = & $MysqlExe @MysqlArgs -N -B -e "SELECT COUNT(*) FROM practice_session WHERE user_id IN ($script:Uid,$script:OtherUid);" 2>$null
    $leftMarks = & $MysqlExe @MysqlArgs -N -B -e "SELECT COUNT(*) FROM user_word_mark WHERE user_id IN ($script:Uid,$script:OtherUid);" 2>$null
    $leftUsers = & $MysqlExe @MysqlArgs -N -B -e "SELECT COUNT(*) FROM sys_user WHERE id IN ($script:Uid,$script:OtherUid);" 2>$null
    Check '测试数据已清理干净（会话 / 标记 / 用户均为 0）' `
        (($leftSessions -eq '0') -and ($leftMarks -eq '0') -and ($leftUsers -eq '0')) `
        "残留 会话=$leftSessions 标记=$leftMarks 用户=$leftUsers"

    Write-Host "`n========== 汇总 ==========" -ForegroundColor Cyan
    Write-Host "  通过 $script:Pass 项，失败 $script:Fail 项"
    Write-Host "  测试会话：阅读 sessionId=$sid，翻译 sessionId=$tsid（已随测试用户一起删除）"
}

if ($script:Fail -eq 0) { exit 0 } else { exit 1 }
