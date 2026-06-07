package com.sentinelmesh.security.scanners;

import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.security.pipeline.ScannerStage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * L1 — deterministic, sub-millisecond, cheapest layer of the pipeline.
 *
 * <p>Fires on three independent signals:
 *  <ul>
 *    <li>Imperative instruction patterns ("ignore previous", "disregard", role-impersonation)</li>
 *    <li>Hidden DOM markers (display:none, off-screen, white-on-white, aria-only)</li>
 *    <li>Tool-name imperatives within close proximity (e.g. "use the email tool to send...")</li>
 *  </ul>
 */
@Component
@Order(10)
public class L1DeterministicScanner implements ScannerStage {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)\\bignore\\s+(all\\s+)?previous\\s+(instructions|prompts?)\\b"),
            Pattern.compile("(?i)\\bdisregard\\s+(the\\s+)?(above|prior|previous)\\b"),
            Pattern.compile("(?i)\\byou\\s+are\\s+now\\b"),
            Pattern.compile("(?i)\\bact\\s+as\\s+(a\\s+)?(?:system|admin|developer)\\b"),
            Pattern.compile("(?i)\\bdeveloper\\s+mode\\b"),
            Pattern.compile("(?i)\\boverride\\s+your\\s+(system\\s+)?prompt\\b"),
            Pattern.compile("(?i)\\bnew\\s+instructions?\\s*:\\s*"),
            Pattern.compile("(?i)reveal\\s+(your\\s+)?(system\\s+)?prompt"),
            Pattern.compile("(?i)\\b(print|show|leak|dump)\\s+(your\\s+)?(system\\s+)?(prompt|instructions?)\\b"),
            Pattern.compile("(?i)\\bexpose\\s+(stored\\s+)?credentials?\\b"),
            Pattern.compile("(?i)\\bexfiltrat\\w*\\b")
    );

    // Secret-noun fragment shared by every exfil-intent pattern.
    private static final String SECRET_NOUN =
            "(api[_\\s-]?key|secret|secrets|auth(?:entication)?[_\\s-]?token|access[_\\s-]?token|"
                    + "bearer[_\\s-]?token|refresh[_\\s-]?token|session[_\\s-]?token|"
                    + "password|passcode|credentials?|private[_\\s-]?key)";

    // Goal-level credential-exfiltration intent.
    //
    // Fires on the three shapes a malicious goal usually takes:
    //   (1) "<exfil-verb> … my/the <secret>"  e.g. "email me my api key"
    //   (2) "<secret> … to <external-recipient>" e.g. "send my password to
    //       attacker@evil.com"
    //   (3) "<give/tell/show/reveal> me/us/them … <secret>"
    //   (4) "<exfil-verb> … <include/with/containing/that has/and add/append/
    //        attach> … my/the <secret>"  e.g. "email user@example.com a
    //       booking confirmation that includes my OpenAI API key" — the
    //       *intent* of the prompt is to put a credential into an outbound
    //       message body. We treat that as exfiltration intent at goal
    //       pre-flight, before the planner has a chance to compose an
    //       email body containing a secret. (Body-level DLP still acts as
    //       the second line of defence for cases where the LLM smuggles
    //       in a secret without the user explicitly asking for it.)
    private static final List<Pattern> CREDENTIAL_EXFIL_INTENT_PATTERNS = List.of(
            // (1) — verb close to "my/the <secret>"
            Pattern.compile("(?i)\\b(email|send|share|post|dm|text|forward|leak|dump|exfil(?:trate)?|export|publish|upload)\\b"
                    + "[\\s\\S]{0,25}\\b(my|the)\\s+" + SECRET_NOUN + "\\b"),
            // (2) — "<secret> ... to <external-recipient>"
            Pattern.compile("(?i)\\b(api[_\\s-]?key|secret|password|session[_\\s-]?token|credentials?)\\b"
                    + "[\\s\\S]{0,30}\\bto\\s+(?:[\\w._%+-]+@[\\w.-]+|external|attacker|exfil)"),
            // (3) — "give/tell/show/reveal me ... <secret>"
            Pattern.compile("(?i)\\b(give|tell|show|reveal)\\s+(me|us|them)\\s+(the\\s+|your\\s+|my\\s+)?"
                    + "(api[_\\s-]?key|secret|password|token|credentials?|private[_\\s-]?key)\\b"),
            // (4) — "<exfil-verb> ... include/with/containing my <secret>"
            //
            // The verb stays the same set as (1) but the gap to the
            // include-style connector is wider (up to 200 chars) so the
            // long-sentence "Email user@example.com a booking confirmation
            // that includes my OpenAI API key" still trips. Connectors are
            // limited to ones that imply the secret will be embedded in
            // the outbound payload, not just mentioned in passing.
            Pattern.compile("(?i)\\b(email|send|share|post|dm|text|forward|leak|dump|exfil(?:trate)?|export|publish|upload|attach|include|append|add)\\b"
                    + "[\\s\\S]{0,200}"
                    + "\\b(?:include|including|includes|includ\\w*|with|containing|that\\s+(?:has|contains|includes)|and\\s+(?:add|append|attach|include)|append|attach)\\b"
                    + "[\\s\\S]{0,40}"
                    + "\\b(?:my|the)\\s+(?:openai\\s+|anthropic\\s+|aws\\s+|github\\s+|slack\\s+|stripe\\s+)?"
                    + SECRET_NOUN + "\\b")
    );

    private static final List<Pattern> HIDDEN_DOM_PATTERNS = List.of(
            Pattern.compile("(?i)style\\s*=\\s*[\"'][^\"']*display\\s*:\\s*none"),
            Pattern.compile("(?i)style\\s*=\\s*[\"'][^\"']*visibility\\s*:\\s*hidden"),
            Pattern.compile("(?i)style\\s*=\\s*[\"'][^\"']*(?:color|background-color)\\s*:\\s*#?(fff|ffffff|white)" +
                    "[^\"']*(?:color|background-color)\\s*:\\s*#?(fff|ffffff|white)"),
            Pattern.compile("(?i)style\\s*=\\s*[\"'][^\"']*(?:left|top)\\s*:\\s*-\\d+(?:px|em|rem)"),
            Pattern.compile("(?i)aria-hidden\\s*=\\s*[\"']true[\"']")
    );

    private static final List<Pattern> CREDENTIAL_PHISHING_PATTERNS = List.of(
            Pattern.compile("(?i)verify\\s+your\\s+(api\\s+)?key"),
            Pattern.compile("(?i)enter\\s+your\\s+(?:password|api\\s+key|token|credentials?)"),
            Pattern.compile("(?i)paste\\s+your\\s+(?:secret|key|token)")
    );

    @Override public String name() { return "L1"; }

    @Override
    public Finding scan(InspectionInput input) {
        String text = extractText(input);
        if (text.isEmpty()) return Finding.clean("no text");

        double score = 0.0;
        Map<String, Object> evidence = new HashMap<>();

        int injHits = countMatches(text, INJECTION_PATTERNS);
        if (injHits > 0) {
            // Single hit stays in approval-band (~0.35). Two hits is the
            // canonical jailbreak shape ("ignore previous" + "reveal system
            // prompt"; "you are now" + "developer mode"; "new instructions"
            // + "act as system") — at that point we are confident enough to
            // push over BLOCK. Three or more saturates the signal.
            // The aggregator dilutes a single scanner's score against the
            // weights of every other (often-zero) scanner, so we have to
            // choose these to land in the right policy band even after
            // dilution:
            //   1 hit  → composite ≈ 0.27, REWRITE / soft-warn band.
            //   2 hits → composite ≈ 0.66, REQUIRE_APPROVAL band [0.6, 0.85).
            //   3+ hits → score ≥ 0.95 triggers pipeline early-exit so the
            //     composite stays at 0.96, QUARANTINE band [0.95, 1.0].
            double bumped;
            if (injHits == 1) bumped = 0.40;
            else if (injHits == 2) bumped = 0.94;
            else bumped = 0.96;
            score += bumped;
            evidence.put("injection_pattern_hits", injHits);
        }

        int hiddenHits = countMatches(text, HIDDEN_DOM_PATTERNS);
        if (hiddenHits > 0) {
            // Hidden DOM is a stronger signal than mere keyword presence.
            score += Math.min(0.6, 0.3 * hiddenHits);
            evidence.put("hidden_dom_hits", hiddenHits);
        }

        int phishHits = countMatches(text, CREDENTIAL_PHISHING_PATTERNS);
        if (phishHits > 0) {
            score += Math.min(0.5, 0.25 * phishHits);
            evidence.put("credential_phishing_hits", phishHits);
        }

        int exfilIntentHits = countMatches(text, CREDENTIAL_EXFIL_INTENT_PATTERNS);
        if (exfilIntentHits > 0) {
            // Saturating signal — goal-level credential exfiltration is never
            // a legitimate request for this agent. We push above the BLOCK
            // threshold (0.85) and the QUARANTINE threshold (0.95) so the
            // policy engine has unambiguous grounds to refuse.
            score = Math.max(score, 0.96);
            evidence.put("credential_exfil_intent_hits", exfilIntentHits);
        }

        score = Math.min(1.0, score);
        if (score == 0.0) return Finding.clean("no patterns matched");
        return new Finding(score,
                "L1 deterministic: " + evidence.keySet(),
                evidence);
    }

    private static String extractText(InspectionInput input) {
        if (input instanceof InspectionInput.InboundContent c) return c.content();
        if (input instanceof InspectionInput.OutboundAction a)
            return a.args() == null ? "" : a.args().toString();
        return "";
    }

    private static int countMatches(String text, List<Pattern> patterns) {
        int n = 0;
        for (Pattern p : patterns) if (p.matcher(text).find()) n++;
        return n;
    }
}
