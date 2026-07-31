param(
    [int]$TimeoutSeconds = 8
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

    $tcp = [System.Net.Sockets.TcpClient]::new()
    try {
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        $task = $tcp.ConnectAsync($target.HostName, 443)
        if (-not $task.Wait([TimeSpan]::FromSeconds($TimeoutSeconds))) {
            throw "TCP timeout after $TimeoutSeconds seconds"
        }
        $watch.Stop()
        $tcpOk = $tcp.Connected
        $tcpDetail = "$($watch.ElapsedMilliseconds) ms"
    } catch {
        $tcpDetail = $_.Exception.GetBaseException().Message
    } finally {
        $tcp.Dispose()
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
