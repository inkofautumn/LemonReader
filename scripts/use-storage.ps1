param([string]$StorageRoot='F:\LemonData')
$taskStorage=[System.IO.Path]::GetFullPath($StorageRoot)
if($taskStorage -notmatch '^[A-Za-z]:\\'){throw 'StorageRoot must be an absolute drive path'}
$env:TEMP=Join-Path $taskStorage 'tmp'
$env:TMP=$env:TEMP
$env:GRADLE_USER_HOME=Join-Path $taskStorage 'gradle'
New-Item -ItemType Directory -Path $env:TEMP,$env:GRADLE_USER_HOME -Force | Out-Null
