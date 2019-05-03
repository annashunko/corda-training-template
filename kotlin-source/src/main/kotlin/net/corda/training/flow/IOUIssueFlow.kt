package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.training.contract.IOUContract
import net.corda.training.contract.IOUContract.Companion.IOU_CONTRACT_ID
import net.corda.training.state.IOUState

/**
 * This is the flow which handles issuance of new IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUIssueFlow(val state: IOUState) : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val txBuilder = TransactionBuilder(notary)
        txBuilder.addOutputState(state, IOU_CONTRACT_ID)
        txBuilder.addCommand(IOUContract.Commands.Issue(), state.participants.map { it.owningKey })
        txBuilder.verify(serviceHub)

        val selfSignedTx = serviceHub.signInitialTransaction(txBuilder)
        val sessions = state.participants.filter { it != ourIdentity }.map { initiateFlow(it) }
        val fullySignedTx = subFlow(CollectSignaturesFlow(selfSignedTx, sessions))
        return subFlow(FinalityFlow(fullySignedTx, sessions))
    }

}

/**
 * This is the flow which signs IOU issuances.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUIssueFlow::class)
class IOUIssueFlowResponder(val flowSession: FlowSession) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction" using (output is IOUState)
            }
        }
        subFlow(signedTransactionFlow)
        subFlow(ReceiveFinalityFlow(flowSession))
    }

}