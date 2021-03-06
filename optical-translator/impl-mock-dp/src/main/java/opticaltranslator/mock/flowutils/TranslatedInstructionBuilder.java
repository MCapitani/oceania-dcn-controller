/*
 * Nextworks S.r.l. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package opticaltranslator.mock.flowutils;

import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.instruction.instruction.ApplyActionsCaseBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.instruction.instruction.apply.actions._case.ApplyActions;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.instruction.instruction.apply.actions._case.ApplyActionsBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.instruction.list.Instruction;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.instruction.list.InstructionBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.model.match.types.rev131026.vlan.match.fields.VlanId;

import java.util.ArrayList;
import java.util.List;

class TranslatedInstructionBuilder {

    private final TranslatedActionBuilder actionBuilder;

    TranslatedInstructionBuilder(TranslatedActionBuilder actionBuilder) {
        this.actionBuilder = actionBuilder;
    }

    List<Instruction> makeInstructions(String outPort, VLanTagAction action, VlanId tag) throws FlowParserException {

        List<Instruction> instructionList = new ArrayList<>();
        ApplyActions applyActions =
                new ApplyActionsBuilder()
                        .setAction(
                                actionBuilder.makeOutAction(outPort, action, tag)
                        )
                        .build();
        instructionList.add(
                new InstructionBuilder().setOrder(0).setInstruction(
                        new ApplyActionsCaseBuilder()
                                .setApplyActions(applyActions)
                                .build()
                ).build()
        );
        return instructionList;
    }
}
