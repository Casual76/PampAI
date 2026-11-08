package dev.pampa.pampai.feature.assistant.profile

import dev.pampa.pampai.core.assistant.research.SourceCandidate
import dev.pampa.pampai.core.assistant.research.SourceIssue

internal fun sourceCandidateStatus(candidate: SourceCandidate): String = if (candidate.supported) {
  listOfNotNull(candidate.account, candidate.student).joinToString(" · ")
} else when (candidate.issue) {
  SourceIssue.UPDATE_REQUIRED -> "Aggiornamento necessario"
  SourceIssue.ACCOUNT_REQUIRED -> "Accesso all’account necessario"
  SourceIssue.SIGNATURE_MISMATCH -> "Versione dell’app non compatibile"
  else -> "Collegamento non disponibile"
}

internal fun sourceCandidateHelp(candidate: SourceCandidate): String = when (candidate.issue) {
  SourceIssue.UPDATE_REQUIRED -> "L’app è installata, ma questa versione non supporta il collegamento verificato per la memoria. Cerca una versione compatibile sul Pampa Store, anche nel canale beta. Poi torna qui e cerca di nuovo le app."
  SourceIssue.ACCOUNT_REQUIRED -> "Apri l’app e accedi all’account da collegare. Poi torna qui e cerca di nuovo le app."
  SourceIssue.SIGNATURE_MISMATCH -> "Per collegare i dati serve una versione con firma compatibile con PampAI. Installa entrambe le app dal Pampa Store e cerca di nuovo le app."
  else -> "L’app è installata, ma non ha risposto al controllo del collegamento. Aprila, controlla l’accesso all’account e riprova."
}
