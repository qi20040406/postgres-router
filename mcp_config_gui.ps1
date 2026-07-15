$jsonText = @'
{
  "mcpServers": {
    "postgres-router": {
      "command": "java",
      "args": ["-jar", "D:/DEVELOP/tools/MCP/postgres-router/target/postgres-router.jar"]
    }
  }
}
'@

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$form = New-Object System.Windows.Forms.Form
$form.Text = 'MCP 配置复制器'
$form.Size = New-Object System.Drawing.Size(560, 320)
$form.StartPosition = 'CenterScreen'
$form.MaximizeBox = $false
$form.FormBorderStyle = 'FixedSingle'
$form.Icon = [System.Drawing.Icon]::ExtractAssociatedIcon((Get-Command powershell).Source)

$label = New-Object System.Windows.Forms.Label
$label.Text = '请将以下 JSON 配置复制到 MCP 配置文件中：'
$label.Location = New-Object System.Drawing.Point(12, 12)
$label.Size = New-Object System.Drawing.Size(520, 20)
$form.Controls.Add($label)

$textBox = New-Object System.Windows.Forms.TextBox
$textBox.Multiline = $true
$textBox.ReadOnly = $true
$textBox.Text = $jsonText
$textBox.Font = New-Object System.Drawing.Font('Consolas', 11)
$textBox.Location = New-Object System.Drawing.Point(12, 38)
$textBox.Size = New-Object System.Drawing.Size(520, 140)
$textBox.ScrollBars = 'Vertical'
$textBox.BackColor = [System.Drawing.Color]::FromArgb(245, 245, 245)
$form.Controls.Add($textBox)

$copyBtn = New-Object System.Windows.Forms.Button
$copyBtn.Text = [char]0x1F4CB + ' 一键复制'
$copyBtn.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 11, [System.Drawing.FontStyle]::Bold)
$copyBtn.Size = New-Object System.Drawing.Size(200, 40)
$copyBtn.Location = New-Object System.Drawing.Point(172, 195)
$copyBtn.BackColor = [System.Drawing.Color]::FromArgb(76, 175, 80)
$copyBtn.ForeColor = [System.Drawing.Color]::White
$copyBtn.FlatStyle = 'Flat'
$copyBtn.FlatAppearance.BorderSize = 0
$copyBtn.Cursor = 'Hand'

$copyBtn.Add_Click({
    [System.Windows.Forms.Clipboard]::SetText($jsonText)
    $copyBtn.Text = [char]0x2705 + ' 已复制!'
    $copyBtn.BackColor = [System.Drawing.Color]::FromArgb(33, 150, 243)
    Start-Sleep -Milliseconds 1200
    $copyBtn.Text = [char]0x1F4CB + ' 一键复制'
    $copyBtn.BackColor = [System.Drawing.Color]::FromArgb(76, 175, 80)
})

$form.Controls.Add($copyBtn)

$tipLabel = New-Object System.Windows.Forms.Label
$tipLabel.Text = [char]0x1F4A1 + ' 提示：将此配置添加到 MCP 客户端的 mcpServers 配置文件中即可'
$tipLabel.Location = New-Object System.Drawing.Point(12, 248)
$tipLabel.Size = New-Object System.Drawing.Size(520, 20)
$tipLabel.ForeColor = [System.Drawing.Color]::Gray
$form.Controls.Add($tipLabel)

[void]$form.ShowDialog()
