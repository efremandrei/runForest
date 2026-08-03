param(
    [int]$TimeoutSeconds = 8,
    [int]$TcpSamples = 5
)

$ErrorActionPreference = "Stop"
$targets = @(
    [pscustomobject]@{ Name = "Cloudflare"; HostName = "www.cloudflare.com"; Url = "https://www.cloudflare.com/cdn-cgi/trace" },
    [pscustomobject]@{ Name = "Google"; HostName = "www.google.com"; Url = "https://www.google.com/generate_204" },
    [pscustomobject]@{ Name = "IETF"; HostName = "www.ietf.org"; Url = "https://www.ietf.org/" }
)

$handler = [System.Net.Http.HttpClientHandler]::new()
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
$results = foreach ($target in $targets) {
    $dnsOk = $false
    $tcpOk = $false
    $httpsOk = $false
    $dnsDetail = ""
    $tcpDetail = ""
    $httpsDetail = ""

    try {
        $addresses = [System.Net.Dns]::GetHostAddresses($target.HostName)
        $dnsOk = $addresses.Count -gt 0
        $dnsDetail = "$($addresses.Count) address(es)"
    } catch {
        $dnsDetail = $_.Exception.Message
    }

    $tcpLatencies = New-Object System.Collections.Generic.List[Int64]
    try {
        for ($i = 0; $i -lt $TcpSamples; $i++) {
            $tcp = [System.Net.Sockets.TcpClient]::new()
            try {
                $watch = [System.Diagnostics.Stopwatch]::StartNew()
                $task = $tcp.ConnectAsync($target.HostName, 443)
                if (-not $task.Wait([TimeSpan]::FromSeconds($TimeoutSeconds))) {
                    throw "TCP timeout after $TimeoutSeconds seconds"
                }
                $watch.Stop()
                if ($tcp.Connected) {
                    $tcpLatencies.Add([int64]$watch.ElapsedMilliseconds)
                }
            } finally {
                $tcp.Dispose()
            }
        }
        $tcpOk = $tcpLatencies.Count -gt 0
        $sorted = @($tcpLatencies | Sort-Object)
        $median = if ($sorted.Count -gt 0) { $sorted[[int][Math]::Floor($sorted.Count / 2)] } else { 0 }
        $tcpDetail = "$($tcpLatencies.Count)/$TcpSamples sample(s), median $median ms"
    } catch {
        $tcpDetail = $_.Exception.GetBaseException().Message
    }

    try {
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Get,
            $target.Url
        )
        $response = $client.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
        ).GetAwaiter().GetResult()
        $watch.Stop()
        $httpsOk = [int]$response.StatusCode -ge 100 -and [int]$response.StatusCode -le 599
        $httpsDetail = "HTTP $([int]$response.StatusCode) in $($watch.ElapsedMilliseconds) ms"
        $response.Dispose()
        $request.Dispose()
    } catch {
        $httpsDetail = $_.Exception.GetBaseException().Message
        $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
        if ($curl) {
            try {
                $watch = [System.Diagnostics.Stopwatch]::StartNew()
                $curlOutput = & $curl.Source -L -I --max-time $TimeoutSeconds -A "runForest-check" $target.Url 2>&1
                $watch.Stop()
                $statusLine = @($curlOutput | Select-String -Pattern '^HTTP/\S+\s+(\d+)') | Select-Object -Last 1
                if ($statusLine -and $statusLine.Matches[0].Groups[1].Success) {
                    $statusCode = [int]$statusLine.Matches[0].Groups[1].Value
                    $httpsOk = $statusCode -ge 100 -and $statusCode -le 599
                    $httpsDetail = "curl fallback HTTP $statusCode in $($watch.ElapsedMilliseconds) ms"
                }
            } catch {
                $httpsDetail = "$httpsDetail; curl fallback failed: $($_.Exception.GetBaseException().Message)"
            }
        }
        if (-not $httpsOk) {
            $node = Get-Command node.exe -ErrorAction SilentlyContinue
            if ($node) {
                try {
                    $nodeScript = "const https=require('https');const url=process.argv[1];const timeout=Number(process.argv[2])*1000;const s=Date.now();const req=https.request(url,{method:'HEAD',headers:{'User-Agent':'runForest-check'},timeout},res=>{console.log('HTTP '+res.statusCode+' in '+(Date.now()-s)+' ms');res.resume();});req.on('error',e=>{console.error(e.message);process.exit(1);});req.on('timeout',()=>req.destroy(new Error('timeout')));req.end();"
                    $nodeOutput = & $node.Source -e $nodeScript $target.Url $TimeoutSeconds 2>&1
                    if ($LASTEXITCODE -eq 0) {
                        $statusLine = @($nodeOutput | Select-String -Pattern 'HTTP\s+(\d+)\s+in\s+(\d+)\s+ms') | Select-Object -Last 1
                        if ($statusLine -and $statusLine.Matches[0].Groups[1].Success) {
                            $statusCode = [int]$statusLine.Matches[0].Groups[1].Value
                            $elapsed = [int]$statusLine.Matches[0].Groups[2].Value
                            $httpsOk = $statusCode -ge 100 -and $statusCode -le 599
                            $httpsDetail = "node fallback HTTP $statusCode in $elapsed ms"
                        }
                    } else {
                        $httpsDetail = "$httpsDetail; node fallback failed: $nodeOutput"
                    }
                } catch {
                    $httpsDetail = "$httpsDetail; node fallback failed: $($_.Exception.GetBaseException().Message)"
                }
            }
        }
    }

    [pscustomobject]@{
        Target = $target.Name
        DNS = $dnsOk
        DNSDetail = $dnsDetail
        TCP = $tcpOk
        TCPDetail = $tcpDetail
        HTTPS = $httpsOk
        HTTPSDetail = $httpsDetail
    }
}

$client.Dispose()
$handler.Dispose()
$results | Format-Table -AutoSize

$dnsPass = @($results | Where-Object DNS).Count
$tcpPass = @($results | Where-Object TCP).Count
$httpsPass = @($results | Where-Object HTTPS).Count
Write-Host "Quorum: DNS $dnsPass/3, TCP $tcpPass/3, HTTPS $httpsPass/3"

if ($dnsPass -lt 2 -or $tcpPass -lt 2 -or $httpsPass -lt 2) {
    exit 1
}
