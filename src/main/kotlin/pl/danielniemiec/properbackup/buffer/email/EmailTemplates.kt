package pl.danielniemiec.properbackup.buffer.email

object EmailTemplates {

    fun trialExpiring(daysLeft: Int, email: String): String = """
        <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;max-width:520px;margin:0 auto;padding:24px">
          <div style="text-align:center;margin-bottom:24px">
            <h1 style="font-size:20px;margin:0;color:#1a1a2e">ProperBackup</h1>
          </div>
          <div style="background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:24px">
            <h2 style="font-size:16px;margin:0 0 12px;color:#1a1a2e">Your trial expires in $daysLeft day${if (daysLeft != 1) "s" else ""}</h2>
            <p style="color:#6b7280;font-size:14px;line-height:1.6;margin:0 0 16px">
              Hi! Your ProperBackup trial for <strong>$email</strong> is ending soon.
              After the trial expires, backup uploads will stop and your data will be retained for 30 days.
            </p>
            <a href="https://panel.properbackup.com/account/subscription"
               style="display:inline-block;background:#2563eb;color:#fff;text-decoration:none;padding:10px 24px;border-radius:8px;font-size:14px;font-weight:600">
              Upgrade now
            </a>
          </div>
          <p style="color:#9ca3af;font-size:12px;text-align:center;margin-top:16px">
            ProperBackup — zero-knowledge encrypted backups
          </p>
        </div>
    """.trimIndent()

    fun trialExpired(email: String): String = """
        <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;max-width:520px;margin:0 auto;padding:24px">
          <div style="text-align:center;margin-bottom:24px">
            <h1 style="font-size:20px;margin:0;color:#1a1a2e">ProperBackup</h1>
          </div>
          <div style="background:#fff;border:1px solid #fecaca;border-radius:12px;padding:24px">
            <h2 style="font-size:16px;margin:0 0 12px;color:#dc2626">Your trial has expired</h2>
            <p style="color:#6b7280;font-size:14px;line-height:1.6;margin:0 0 16px">
              The trial for <strong>$email</strong> has ended. Backup uploads have been paused.
              Your existing data will be retained for 30 days. Upgrade now to resume protection.
            </p>
            <a href="https://panel.properbackup.com/account/subscription"
               style="display:inline-block;background:#dc2626;color:#fff;text-decoration:none;padding:10px 24px;border-radius:8px;font-size:14px;font-weight:600">
              Upgrade to continue
            </a>
          </div>
          <p style="color:#9ca3af;font-size:12px;text-align:center;margin-top:16px">
            ProperBackup — zero-knowledge encrypted backups
          </p>
        </div>
    """.trimIndent()

    fun verificationFailed(serverName: String, objectName: String, detail: String): String = """
        <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;max-width:520px;margin:0 auto;padding:24px">
          <div style="text-align:center;margin-bottom:24px">
            <h1 style="font-size:20px;margin:0;color:#1a1a2e">ProperBackup</h1>
          </div>
          <div style="background:#fff;border:1px solid #fecaca;border-radius:12px;padding:24px">
            <h2 style="font-size:16px;margin:0 0 12px;color:#dc2626">⚠ Backup verification failed</h2>
            <p style="color:#6b7280;font-size:14px;line-height:1.6;margin:0 0 8px">
              A backup archive on <strong>$serverName</strong> failed integrity verification:
            </p>
            <div style="background:#fef2f2;border-radius:8px;padding:12px;margin-bottom:16px">
              <p style="font-size:13px;color:#1a1a2e;margin:0 0 4px"><strong>Archive:</strong> $objectName</p>
              <p style="font-size:13px;color:#dc2626;margin:0"><strong>Detail:</strong> $detail</p>
            </div>
            <a href="https://panel.properbackup.com/servers"
               style="display:inline-block;background:#2563eb;color:#fff;text-decoration:none;padding:10px 24px;border-radius:8px;font-size:14px;font-weight:600">
              View in panel
            </a>
          </div>
          <p style="color:#9ca3af;font-size:12px;text-align:center;margin-top:16px">
            ProperBackup — zero-knowledge encrypted backups
          </p>
        </div>
    """.trimIndent()

    fun emailVerification(email: String, verifyUrl: String): String = """
        <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;max-width:520px;margin:0 auto;padding:24px">
          <div style="text-align:center;margin-bottom:24px">
            <h1 style="font-size:20px;margin:0;color:#1a1a2e">ProperBackup</h1>
          </div>
          <div style="background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:24px">
            <h2 style="font-size:16px;margin:0 0 12px;color:#1a1a2e">Confirm your email address</h2>
            <p style="color:#6b7280;font-size:14px;line-height:1.6;margin:0 0 16px">
              Thanks for signing up! Please confirm your email address <strong>$email</strong> by clicking the button below.
            </p>
            <a href="$verifyUrl"
               style="display:inline-block;background:#2563eb;color:#fff;text-decoration:none;padding:10px 24px;border-radius:8px;font-size:14px;font-weight:600">
              Verify email
            </a>
            <p style="color:#9ca3af;font-size:12px;margin-top:16px">
              This link expires in 24 hours. If you didn't create this account, you can safely ignore this email.
            </p>
          </div>
          <p style="color:#9ca3af;font-size:12px;text-align:center;margin-top:16px">
            ProperBackup — zero-knowledge encrypted backups
          </p>
        </div>
    """.trimIndent()

    fun backupStale(serverName: String, serverId: String, hoursSinceLastBackup: Long): String = """
        <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;max-width:520px;margin:0 auto;padding:24px">
          <div style="text-align:center;margin-bottom:24px">
            <h1 style="font-size:20px;margin:0;color:#1a1a2e">ProperBackup</h1>
          </div>
          <div style="background:#fff;border:1px solid #fed7aa;border-radius:12px;padding:24px">
            <h2 style="font-size:16px;margin:0 0 12px;color:#d97706">Backup agent not reporting</h2>
            <p style="color:#6b7280;font-size:14px;line-height:1.6;margin:0 0 16px">
              Server <strong>$serverName</strong> ($serverId) hasn't sent a backup in
              <strong>${hoursSinceLastBackup}h</strong>. The agent may have stopped or the machine may be offline.
            </p>
            <a href="https://panel.properbackup.com/servers"
               style="display:inline-block;background:#2563eb;color:#fff;text-decoration:none;padding:10px 24px;border-radius:8px;font-size:14px;font-weight:600">
              Check status
            </a>
          </div>
          <p style="color:#9ca3af;font-size:12px;text-align:center;margin-top:16px">
            ProperBackup — zero-knowledge encrypted backups
          </p>
        </div>
    """.trimIndent()
}
