# API 冒烟测试 —— 覆盖「阅读题 → 翻译题 → 登录与查询接口」三条链路
#
# 用法（应用需已启动；默认 8080）：
#   powershell -ExecutionPolicy Bypass -File tools\api-smoke.ps1
#   powershell -ExecutionPolicy Bypass -File tools\api-smoke.ps1 -Port 18081
#
# ⚠ 三条硬性约定，破坏任何一条都会让结果不可信：
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
#   3. 应用必须以 app.dev-mode=true 启动（见下方预检）。
#      本脚本大量使用 X-Debug-User-Id 与演示账号，两者都受该开关控制。
#
# 为什么不写成单元测试：这些接口的价值在于「分层是否真的接通了」——
# Service 层的 47 项自检已经证明业务逻辑正确，这里要证明的是
# Controller / JSON 序列化 / 异常映射 / HTTP 状态码这一层也对。

# ⚠ param() 必须是脚本里【第一条可执行语句】（注释之前可以），
#   放到任何赋值之后都会报「不允许在此位置使用 param 关键字」。
param([int]$Port = 8080)

$ErrorActionPreference = 'Continue'
$Base = "http://127.0.0.1:$Port"
$MysqlExe = 'D:\MySQL\MySQL Server 8.0\bin\mysql.exe'
$MysqlArgs = @('-uroot', '-p123456', '--default-character-set=utf8mb4', 'english_question_bank')
$script:Pass = 0
$script:Fail = 0

# ---------------------------------------------------------------------------
# 预检：应用在不在？app.dev-mode 开着没？
#
# 为什么必须有这一段：本脚本的绝大多数断言走 X-Debug-User-Id 与演示账号，
# 而这两样都受 app.dev-mode 控制。若不先检，dev-mode=false 时的表现是
# 「86 项断言集体失败」—— 看起来像代码全烂了，实际只是配置关了。
# 提前给一句明确的话，能省掉很多排查时间。
# ---------------------------------------------------------------------------
try {
    $pre = [Text.Encoding]::UTF8.GetBytes('{"username":"demo","password":"demo123"}')
    Invoke-WebRequest -Method Post -Uri "$Base/api/auth/login" -UseBasicParsing -TimeoutSec 10 `
        -ContentType 'application/json' -Body $pre | Out-Null
    Write-Host "预检通过：应用在 $Base，演示账号可登录" -ForegroundColor DarkGray
}
catch {
    $resp = $_.Exception.Response
    if ($null -eq $resp) {
        Write-Host "❌ 连不上 $Base" -ForegroundColor Red
        Write-Host "   请先启动应用（见 AGENTS.md「常用命令」）。若应用跑在别的端口，用 -Port 指定。" -ForegroundColor Yellow
        exit 2
    }
    if ([int]$resp.StatusCode -eq 401) {
        Write-Host "❌ demo/demo123 登录被拒，自动化测试无法继续。" -ForegroundColor Red
        Write-Host "   最可能的原因：application.yml 里 app.dev-mode=false。" -ForegroundColor Yellow
        Write-Host "   那是【上线前的正确配置】（演示弱口令不会被补设），但本脚本需要它开着。" -ForegroundColor Yellow
        Write-Host "   本地测试请把 app.dev-mode 改为 true，或先用注册接口建一个有密码的账号。" -ForegroundColor Yellow
        exit 3
    }
    Write-Host ("❌ 预检失败：HTTP " + [int]$resp.StatusCode) -ForegroundColor Red
    exit 4
}

# 第二道预检：X-Debug-User-Id 是否可用。
#
# 为什么单靠上面那道不够（本次实测踩到）：demo 的密码一旦写进库，就不会因为
# app.dev-mode=false 而消失（配置不该改数据），所以此时【登录仍然成功】，
# 第一道预检会通过 —— 然后本脚本里所有走 X-Debug-User-Id 的断言集体失败，
# 看起来像代码全烂了。必须单独验证这个请求头确实被接受。
$demoId = (& $MysqlExe @MysqlArgs -N -B -e "SELECT id FROM sys_user WHERE username='demo';" 2>$null |
           Where-Object { $_ -match '^\d+$' } | Select-Object -First 1)
if ($demoId) {
    try {
        Invoke-WebRequest -Uri "$Base/api/me/vocabulary" -UseBasicParsing -TimeoutSec 10 `
            -Headers @{ 'X-Debug-User-Id' = "$demoId" } | Out-Null
    }
    catch {
        $resp = $_.Exception.Response
        if ($resp -and [int]$resp.StatusCode -eq 401) {
            Write-Host "❌ X-Debug-User-Id 被拒绝 —— 说明 app.dev-mode=false。" -ForegroundColor Red
            Write-Host "   本脚本大量断言依赖它，请把 application.yml 的 app.dev-mode 改为 true 后重启应用。" -ForegroundColor Yellow
            Write-Host "   （注意：演示密码已在库里，所以关闭 dev-mode 不会让登录失败，只会关掉这个请求头。）" -ForegroundColor Yellow
            exit 5
        }
        $code = if ($resp) { [int]$resp.StatusCode } else { '无响应' }
        Write-Host ("⚠️ 第二道预检出现非 401 错误：HTTP " + $code) -ForegroundColor Yellow
    }
}
else {
    Write-Host "⚠️ 库里没有 demo 用户，跳过 X-Debug-User-Id 预检" -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# 测试用户：直接写库创建（本项目还没有注册接口），结束时连同其数据一起删除
# ---------------------------------------------------------------------------

function New-SmokeUser {
    param([string]$Name)

    # 直接从 demo 用户【复制 BCrypt 哈希】，让测试用户能用同一个密码（demo123）登录。
    #
    # 为什么这么做：脚本是在数据库层面直接建用户的（本项目没有注册接口），
    # 而 BCrypt 哈希没法用 SQL 算出来。复制一个已知密码的哈希，
    # 就得到一个「密码已知」的测试账号，且不必把明文或哈希硬编码进脚本。
    # demo 用户必须先跑过一次应用（SampleDataInitializer 会给它补设密码），否则这里复制到 NULL。
    $sql = @"
INSERT INTO sys_user (username, nickname, password_hash)
SELECT '$Name', 'API 冒烟测试', (SELECT password_hash FROM sys_user WHERE username = 'demo');
SELECT LAST_INSERT_ID();
"@
    $out = & $MysqlExe @MysqlArgs -N -B -e $sql 2>$null
    $id = $out | Where-Object { $_ -match '^\d+$' } | Select-Object -Last 1
    if (-not $id) { throw "创建测试用户失败：$Name" }
    return [int]$id
}

function Remove-SmokeUser {
    param([int]$Id)
    # ⚠ 顺序不能反：fk_sess_user / fk_mark_user / fk_vocab_user / fk_token_user
    #   都是【不带 ON DELETE CASCADE】的普通外键，直接删 sys_user 会被子表挡住。
    #   先删 practice_session（它会级联带走 session_question / answer_record /
    #   translation_grading / 该会话下的 user_word_mark），
    #   再删无会话的标记、生词本，最后才是 sys_user。
    $sql = @"
DELETE FROM user_token       WHERE user_id = $Id;
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
        [int]$UserId = $script:Uid,    # 0 表示不带开发期身份头
        [string]$Bearer = $null        # 传入令牌则用 Authorization: Bearer
    )
    $headers = @{}
    if ($UserId -gt 0) { $headers['X-Debug-User-Id'] = "$UserId" }
    if ($Bearer) { $headers['Authorization'] = "Bearer $Bearer" }

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

    Write-Host "`n========== 三、登录与查询接口 ==========" -ForegroundColor Cyan

    # ---------- 1. 登录 ----------
    $r = Invoke-Api POST '/api/auth/login' @{ username = "api_smoke_$stamp"; password = 'demo123' } -UserId 0
    $login = $r.Body | ConvertFrom-Json
    $token = $login.data.token
    Check '登录成功并拿到令牌' (($r.Code -eq 200) -and ($token.Length -gt 20)) "HTTP $($r.Code) token 长度=$($token.Length)"
    Check '登录响应不含密码哈希' ($r.Body -notmatch 'passwordHash|\$2a\$')
    Check '登录响应含用户信息' ($login.data.user.username -eq "api_smoke_$stamp") "user=$($login.data.user.username) role=$($login.data.user.role)"

    $r = Invoke-Api POST '/api/auth/login' @{ username = "api_smoke_$stamp"; password = 'definitely-wrong' } -UserId 0
    $msgWrongPwd = ($r.Body | ConvertFrom-Json).message
    Check '密码错误 → 401' ($r.Code -eq 401) "HTTP $($r.Code)"

    $r = Invoke-Api POST '/api/auth/login' @{ username = "no_such_user_$stamp"; password = 'definitely-wrong' } -UserId 0
    Check '用户名不存在与密码错误提示一致（防账号枚举）' `
        (($r.Code -eq 401) -and ((($r.Body | ConvertFrom-Json).message) -eq $msgWrongPwd)) `
        "message=$(($r.Body | ConvertFrom-Json).message)"

    # ---------- 2. 令牌鉴权 ----------
    $r = Invoke-Api GET '/api/me/vocabulary' $null -UserId 0 -Bearer $token
    Check '用 Bearer 令牌访问受保护接口' ($r.Code -eq 200) "HTTP $($r.Code)"

    $r = Invoke-Api GET '/api/me/vocabulary' $null -UserId 0 -Bearer 'forged-token-xxxxx'
    Check '伪造令牌 → 401' ($r.Code -eq 401) "message=$(($r.Body | ConvertFrom-Json).message)"

    # ---------- 3. 生词本 ----------
    $r = Invoke-Api GET '/api/me/vocabulary?page=1&size=50'
    $vocab = $r.Body | ConvertFrom-Json
    # 前两节一共标了 3 个不同的词：run / harvest / abandon
    Check '生词本按原形去重后有 3 个词' ($vocab.data.total -eq 3) "total=$($vocab.data.total)"
    Check '生词本条目带中文释义' ($null -ne $vocab.data.records[0].translation) "first=$($vocab.data.records[0].normalizedForm) → $($vocab.data.records[0].translation)"
    # ⚠ 必须取 records[0] 再问它有哪些属性：records 本身是数组，
    #   在数组的 PSObject.Properties 里找不到 phoneticUk 这个字段名。
    Check '生词本条目带音标字段' ($vocab.data.records[0].PSObject.Properties.Name -contains 'phoneticUk') `
        "字段=$(($vocab.data.records[0].PSObject.Properties.Name) -join ',')"
    Check 'run 的累计标记次数为 2' ((($vocab.data.records | Where-Object { $_.normalizedForm -eq 'run' }).markCount) -eq 2)

    # ---------- 4. 错题本：核心安全断言 ----------
    # 前两节共产生 2 条已交卷的错题（阅读 q1、q2 都答错了）
    $r = Invoke-Api GET '/api/me/wrong-questions?page=1&size=50'
    $wrongBefore = ($r.Body | ConvertFrom-Json).data.total
    Check '错题本先有 2 条已交卷错题' ($wrongBefore -eq 2) "total=$wrongBefore"

    # 新开一次练习，故意答错，但【不交卷】
    $r = Invoke-Api POST '/api/practices/reading' @{ passageId = 1 }
    $sid3 = ($r.Body | ConvertFrom-Json).data.sessionId
    $q3 = ($r.Body | ConvertFrom-Json).data.questions[0].id
    $r = Invoke-Api POST "/api/practices/$sid3/answers" @{ questionId = $q3; userAnswer = 'D' }
    # 先确认这一题【确实被判错了】，否则下面的断言会因为「答对了」而假通过
    $isWrong = -not (($r.Body | ConvertFrom-Json).data.isCorrect)
    Check '新会话中该题确实答错了' $isWrong "isCorrect=$((($r.Body | ConvertFrom-Json).data.isCorrect))"

    $r = Invoke-Api GET '/api/me/wrong-questions?page=1&size=50'
    $wrongDuring = ($r.Body | ConvertFrom-Json).data.total
    Check '【安全】未交卷的错题【不进】错题本（红线 2）' ($wrongDuring -eq $wrongBefore) `
        "未交卷时 total 仍为 $wrongDuring（期望 $wrongBefore）"

    $r = Invoke-Api POST "/api/practices/$sid3/finish"
    $r = Invoke-Api GET '/api/me/wrong-questions?page=1&size=50'
    $wrongAfter = ($r.Body | ConvertFrom-Json).data.total
    Check '交卷后该错题进入错题本' ($wrongAfter -eq $wrongBefore + 1) "total=$wrongAfter（期望 $($wrongBefore + 1)）"
    $r = Invoke-Api GET '/api/me/wrong-questions?page=1&size=50'
    $wrongList = $r.Body | ConvertFrom-Json
    Check '错题本条目带正确答案（阅读题）' ($null -ne $wrongList.data.records[0].correctOptionKey) "correctOptionKey=$($wrongList.data.records[0].correctOptionKey)"
    Check '错题本条目带来源文章标题' ($null -ne $wrongList.data.records[0].passageTitle) "passageTitle=$($wrongList.data.records[0].passageTitle)"
    Check '错题本按作答时间倒序（最新那条是我刚做错的 D）' ($wrongList.data.records[0].userAnswer -eq 'D') "first.userAnswer=$($wrongList.data.records[0].userAnswer)"

    # ---------- 5. 练习历史 ----------
    $r = Invoke-Api GET '/api/practices?page=1&size=50'
    $hist = $r.Body | ConvertFrom-Json
    # 阅读会话 ×2 + 翻译会话 ×1 = 3
    Check '练习历史含 3 次练习' ($hist.data.total -eq 3) "total=$($hist.data.total)"
    Check '阅读历史的条目带文章标题' (($hist.data.records | Where-Object { $_.mode -eq 'READING' })[0].passageTitle -eq 'Community Gardens') `
        "passageTitle=$((($hist.data.records | Where-Object { $_.mode -eq 'READING' })[0]).passageTitle)"
    Check '翻译题历史的 passageTitle 为 null（LEFT JOIN 生效，没被过滤掉）' `
        ($null -eq (($hist.data.records | Where-Object { $_.mode -eq 'TRANSLATION' })[0]).passageTitle) `
        "翻译会话仍在列表里，共 $(Count-Of ($hist.data.records | Where-Object { $_.mode -eq 'TRANSLATION' })) 条"

    # ---------- 6. 分页语义 ----------
    $r = Invoke-Api GET '/api/practices?page=1&size=1'
    $p1 = ($r.Body | ConvertFrom-Json).data
    Check 'size=1 只返回 1 条' ((Count-Of $p1.records) -eq 1) "records=$((Count-Of $p1.records))"
    Check '分页带回了总数（COUNT 查询生效）' ($p1.total -eq 3) "total=$($p1.total)"
    Check '分页带回总页数' ($p1.pages -eq 3) "pages=$($p1.pages)"
    Check 'hasNext=true（还有下一页）' ($p1.hasNext -eq $true) "hasNext=$($p1.hasNext)"

    $r = Invoke-Api GET '/api/practices?page=3&size=1'
    $p3 = ($r.Body | ConvertFrom-Json).data
    Check '最后一页 hasNext=false' (($p3.hasNext -eq $false) -and ((Count-Of $p3.records) -eq 1)) "hasNext=$($p3.hasNext)"

    $r = Invoke-Api GET '/api/practices?page=99&size=1'
    $pOut = ($r.Body | ConvertFrom-Json).data
    Check '页码超出范围返回空列表，而不是绕回第一页' ((Count-Of $pOut.records) -eq 0) "records=$((Count-Of $pOut.records))"

    # size<=0 时 MyBatis-Plus 会「不分页、返回全表」—— Paging 工具类必须在入口挡住
    $r = Invoke-Api GET '/api/practices?page=1&size=0'
    $pZero = ($r.Body | ConvertFrom-Json).data
    Check 'size=0 被归一化为 20（否则会返回全表）' ($pZero.size -eq 20) "实际生效 size=$($pZero.size)"

    $r = Invoke-Api GET '/api/practices?page=1&size=99999'
    $pBig = ($r.Body | ConvertFrom-Json).data
    Check 'size=99999 被上限截到 100' ($pBig.size -eq 100) "实际生效 size=$($pBig.size)"

    $r = Invoke-Api GET '/api/practices?page=0&size=1'
    $pZeroPage = ($r.Body | ConvertFrom-Json).data
    Check 'page=0 被归一化为第 1 页' ($pZeroPage.page -eq 1) "实际生效 page=$($pZeroPage.page)"

    # ---------- 7. 越权隔离 ----------
    # 越权对照用户没有登录过，它的生词本必须是空的（数据不能串）
    $r = Invoke-Api GET '/api/me/vocabulary?page=1&size=50' -UserId $script:OtherUid
    Check '另一个用户的生词本是空的（数据不串）' ((($r.Body | ConvertFrom-Json).data.total) -eq 0) `
        "total=$((($r.Body | ConvertFrom-Json).data.total))"

    # ---------- 7.5 错误码：不存在的地址必须是 404，不能是 500（B-15）----------
    # 这一组是回归红线：修好之后不能再退化回 500。
    $r = Invoke-Api GET '/no-such-page' $null -UserId 0
    Check '不存在的地址 → 404（不是 500）' ($r.Code -eq 404) "HTTP $($r.Code) message=$(($r.Body|ConvertFrom-Json).message)"

    $r = Invoke-Api GET '/api/no-such-endpoint' $null -UserId 0
    Check '不存在的 API 路径 → 404' ($r.Code -eq 404) "HTTP $($r.Code)"

    # 带尾斜杠的 /swagger-ui/ 在 springdoc 下不会自动跳转，应当是 404 而不是 500
    $r = Invoke-Api GET '/swagger-ui/' $null -UserId 0
    Check '/swagger-ui/ → 404（修复前这里是 500）' ($r.Code -eq 404) "HTTP $($r.Code)"

    # 对只接受 POST 的登录接口发 GET → Spring 抛 HttpRequestMethodNotSupportedException
    $r = Invoke-Api GET '/api/auth/login' $null -UserId 0
    Check 'GET 打 POST-only 接口 → 405（不是 500）' ($r.Code -eq 405) "HTTP $($r.Code)"

    # ---------- 8. 登出 ----------
    $r = Invoke-Api POST '/api/auth/logout' $null -UserId 0 -Bearer $token
    Check '登出返回 200' ($r.Code -eq 200) "HTTP $($r.Code)"
    $r = Invoke-Api GET '/api/me/vocabulary' $null -UserId 0 -Bearer $token
    Check '登出后令牌失效 → 401' ($r.Code -eq 401) "message=$(($r.Body | ConvertFrom-Json).message)"
}
finally {
    # 无论成败都要清理，否则残留数据会让 WordMarkVerifier / AnswerVerifier 误报
    Remove-SmokeUser $script:Uid
    Remove-SmokeUser $script:OtherUid

    $leftSessions = & $MysqlExe @MysqlArgs -N -B -e "SELECT COUNT(*) FROM practice_session WHERE user_id IN ($script:Uid,$script:OtherUid);" 2>$null
    $leftMarks = & $MysqlExe @MysqlArgs -N -B -e "SELECT COUNT(*) FROM user_word_mark WHERE user_id IN ($script:Uid,$script:OtherUid);" 2>$null
    $leftUsers = & $MysqlExe @MysqlArgs -N -B -e "SELECT COUNT(*) FROM sys_user WHERE id IN ($script:Uid,$script:OtherUid);" 2>$null
    $leftTokens = & $MysqlExe @MysqlArgs -N -B -e "SELECT COUNT(*) FROM user_token WHERE user_id IN ($script:Uid,$script:OtherUid);" 2>$null
    Check '测试数据已清理干净（会话 / 标记 / 令牌 / 用户均为 0）' `
        (($leftSessions -eq '0') -and ($leftMarks -eq '0') -and ($leftUsers -eq '0') -and ($leftTokens -eq '0')) `
        "残留 会话=$leftSessions 标记=$leftMarks 令牌=$leftTokens 用户=$leftUsers"

    Write-Host "`n========== 汇总 ==========" -ForegroundColor Cyan
    Write-Host "  通过 $script:Pass 项，失败 $script:Fail 项"
    Write-Host "  测试会话：阅读 sessionId=$sid，翻译 sessionId=$tsid（已随测试用户一起删除）"
}

if ($script:Fail -eq 0) { exit 0 } else { exit 1 }
