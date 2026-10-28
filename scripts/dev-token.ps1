# Gera um JWT HS256 para o perfil "local" (desenvolvimento). Uso:
#   .\scripts\dev-token.ps1 [-ClientId sistema-a] [-Scopes "requests:read requests:write"]
param(
    [string]$ClientId = "sistema-a",
    [string]$Scopes = "requests:read requests:write",
    [string]$Secret = $(if ($env:JWT_LOCAL_SECRET) { $env:JWT_LOCAL_SECRET } else { "local-development-secret-change-me-0123456789" }),
    [string]$Issuer = "http://localhost/local-issuer",
    [string]$Audience = "activation-service"
)

function ConvertTo-Base64Url([byte[]]$Bytes) {
    [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$header = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
$claims = [ordered]@{ iss = $Issuer; aud = @($Audience); sub = $ClientId; client_id = $ClientId; scope = $Scopes; iat = $now; exp = $now + 3600 }
$payload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes(($claims | ConvertTo-Json -Compress)))
$hmac = New-Object Security.Cryptography.HMACSHA256 (, [Text.Encoding]::UTF8.GetBytes($Secret))
$signature = ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$header.$payload")))
"$header.$payload.$signature"
