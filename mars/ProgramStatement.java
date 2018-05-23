package mars;

import mars.assembler.SymbolTable;
import mars.assembler.Token;
import mars.assembler.TokenList;
import mars.assembler.TokenTypes;
import mars.mips.hardware.Coprocessor1;
import mars.mips.hardware.RegisterFile;
import mars.mips.instructions.BasicInstruction;
import mars.mips.instructions.BasicInstructionFormat;
import mars.mips.instructions.Instruction;
import mars.util.Binary;

import java.util.ArrayList;

/*
Copyright (c) 2003-2013,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining 
a copy of this software and associated documentation files (the 
"Software"), to deal in the Software without restriction, including 
without limitation the rights to use, copy, modify, merge, publish, 
distribute, sublicense, and/or sell copies of the Software, and to 
permit persons to whom the Software is furnished to do so, subject 
to the following conditions:

The above copyright notice and this permission notice shall be 
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF 
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. 
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR 
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * Represents one assembly/machine statement.  This represents the "bare machine" level.
 * Pseudo-instructions have already been processed at this point and each assembly
 * statement generated by them is one of these.
 *
 * @author Pete Sanderson and Jason Bumgarner
 * @version August 2003
 */


public class ProgramStatement {
    private MIPSprogram sourceMIPSprogram;
    private String source, basicAssemblyStatement, machineStatement;
    private TokenList originalTokenList, strippedTokenList;
    private BasicStatementList basicStatementList;
    private int[] operands;
    private int numOperands;
    private Instruction instruction;
    private int textAddress;
    private int sourceLine;
    private int binaryStatement;
    private boolean altered;
    private static final String invalidOperator = "<INVALID>";

    //////////////////////////////////////////////////////////////////////////////////

    /**
     * Constructor for ProgramStatement when there are links back to all source and token
     * information.  These can be used by a debugger later on.
     *
     * @param sourceMIPSprogram The MIPSprogram object that contains this statement
     * @param source            The corresponding MIPS source statement.
     * @param origTokenList     Complete list of Token objects (includes labels, comments, parentheses, etc)
     * @param strippedTokenList List of Token objects with all but operators and operands removed.
     * @param inst              The Instruction object for this statement's operator.
     * @param textAddress       The Text Segment address in memory where the binary machine code for this statement
     *                          is stored.
     **/
    public ProgramStatement(MIPSprogram sourceMIPSprogram, String source, TokenList origTokenList, TokenList strippedTokenList,
                            Instruction inst, int textAddress, int sourceLine) {
        this.sourceMIPSprogram = sourceMIPSprogram;
        this.source = source;
        this.originalTokenList = origTokenList;
        this.strippedTokenList = strippedTokenList;
        this.operands = new int[4];
        this.numOperands = 0;
        this.instruction = inst;
        this.textAddress = textAddress;
        this.sourceLine = sourceLine;
        this.basicAssemblyStatement = null;
        this.basicStatementList = new BasicStatementList();
        this.machineStatement = null;
        this.binaryStatement = 0;  // nop, or sll $0, $0, 0  (32 bits of 0's)
        this.altered = false;
    }


    //////////////////////////////////////////////////////////////////////////////////

    /**
     * Constructor for ProgramStatement used only for writing a binary machine
     * instruction with no source code to refer back to.  Originally supported
     * only NOP instruction (all zeroes), but extended in release 4.4 to support
     * all basic instructions.  This was required for the self-modifying code
     * feature.
     *
     * @param binaryStatement The 32-bit machine code.
     * @param textAddress     The Text Segment address in memory where the binary machine code for this statement
     *                        is stored.
     **/
    public ProgramStatement(int binaryStatement, int textAddress) {
        this.sourceMIPSprogram = null;
        this.binaryStatement = binaryStatement;
        this.textAddress = textAddress;
        this.originalTokenList = this.strippedTokenList = null;
        this.source = "";
        this.machineStatement = this.basicAssemblyStatement = null;
        BasicInstruction instr = Globals.instructionSet.findByBinaryCode(binaryStatement);
        if (instr == null) {
            this.operands = null;
            this.numOperands = 0;
            this.instruction = (binaryStatement == 0) // this is a "nop" statement
                    ? (Instruction) Globals.instructionSet.matchOperator("nop").get(0)
                    : null;
        } else {
            this.operands = new int[4];
            this.numOperands = 0;
            this.instruction = instr;

            String opandCodes = "fst";
            String fmt = instr.getOperationMask();
            BasicInstructionFormat instrFormat = instr.getInstructionFormat();
            int numOps = 0;
            for (int i = 0; i < opandCodes.length(); i++) {
                int code = opandCodes.charAt(i);
                int j = fmt.indexOf(code);
                if (j >= 0) {
                    int k0 = 31 - fmt.lastIndexOf(code);
                    int k1 = 31 - j;
                    int opand = (binaryStatement >> k0) & ((1 << (k1 - k0 + 1)) - 1);
                    if (instrFormat.equals(BasicInstructionFormat.I_BRANCH_FORMAT) && numOps == 2) {
                        opand = opand << 16 >> 16;
                    } else if (instrFormat.equals(BasicInstructionFormat.J_FORMAT) && numOps == 0) {
                        opand |= (textAddress >> 2) & 0x3C000000;
                    }
                    this.operands[numOps] = opand;
                    numOps++;
                }
            }
            this.numOperands = numOps;
        }
        this.altered = false;
        this.basicStatementList = buildBasicStatementListFromBinaryCode(binaryStatement, instr, operands, numOperands);
    }


    /////////////////////////////////////////////////////////////////////////////

    /**
     * Given specification of BasicInstruction for this operator, build the
     * corresponding assembly statement in basic assembly format (e.g. substituting
     * register numbers for register names, replacing labels by values).
     *
     * @param errors The list of assembly errors encountered so far.  May add to it here.
     **/
    public void buildBasicStatementFromBasicInstruction(ErrorList errors) {
        Token token = strippedTokenList.get(0);
        String basicStatementElement = token.getValue() + " ";
        ;
        String basic = basicStatementElement;
        basicStatementList.addString(basicStatementElement); // the operator
        TokenTypes tokenType, nextTokenType;
        String tokenValue;
        int registerNumber;
        this.numOperands = 0;
        for (int i = 1; i < strippedTokenList.size(); i++) {
            token = strippedTokenList.get(i);
            tokenType = token.getType();
            tokenValue = token.getValue();
            if (tokenType == TokenTypes.REGISTER_NUMBER) {
                basicStatementElement = tokenValue;
                basic += basicStatementElement;
                basicStatementList.addString(basicStatementElement);
                try {
                    registerNumber = RegisterFile.getUserRegister(tokenValue).getNumber();
                } catch (Exception e) {
                    // should never happen; should be caught before now...
                    errors.add(new ErrorMessage(this.sourceMIPSprogram, token.getSourceLine(), token.getStartPos(), "invalid register name"));
                    return;
                }
                this.operands[this.numOperands++] = registerNumber;
            } else if (tokenType == TokenTypes.REGISTER_NAME) {
                registerNumber = RegisterFile.getNumber(tokenValue);
                basicStatementElement = "$" + registerNumber;
                basic += basicStatementElement;
                basicStatementList.addString(basicStatementElement);
                if (registerNumber < 0) {
                    // should never happen; should be caught before now...
                    errors.add(new ErrorMessage(this.sourceMIPSprogram, token.getSourceLine(), token.getStartPos(), "invalid register name"));
                    return;
                }
                this.operands[this.numOperands++] = registerNumber;
            } else if (tokenType == TokenTypes.FP_REGISTER_NAME) {
                registerNumber = Coprocessor1.getRegisterNumber(tokenValue);
                basicStatementElement = "$f" + registerNumber;
                basic += basicStatementElement;
                basicStatementList.addString(basicStatementElement);
                if (registerNumber < 0) {
                    // should never happen; should be caught before now...
                    errors.add(new ErrorMessage(this.sourceMIPSprogram, token.getSourceLine(), token.getStartPos(), "invalid FPU register name"));
                    return;
                }
                this.operands[this.numOperands++] = registerNumber;
            } else if (tokenType == TokenTypes.IDENTIFIER) {
                int address = this.sourceMIPSprogram.getLocalSymbolTable().getAddressLocalOrGlobal(tokenValue);
                if (address == SymbolTable.NOT_FOUND) { // symbol used without being defined
                    errors.add(new ErrorMessage(this.sourceMIPSprogram, token.getSourceLine(), token.getStartPos(),
                            "Symbol \"" + tokenValue + "\" not found in symbol table."));
                    return;
                }
                boolean absoluteAddress = true; // (used below)
                //////////////////////////////////////////////////////////////////////
                // added code 12-20-2004. If basic instruction with I_BRANCH format, then translate
                // address from absolute to relative and shift left 2.
                //
                // DPS 14 June 2007: Apply delayed branching if enabled.  This adds 4 bytes to the
                // address used to calculate branch distance in relative words.
                //
                // DPS 4 January 2008: Apply the delayed branching 4-byte (instruction length) addition
                // regardless of whether delayed branching is enabled or not.  This was in response to
                // several people complaining about machine code not matching that from the COD3 example
                // on p 98-99.  In that example, the branch offset reflect delayed branching because
                // all MIPS machines implement delayed branching.  But the topic of delayed branching
                // is not yet introduced at that point, and instructors want to avoid the messiness
                // that comes along with it.  Our original strategy was to do it like SPIM does, which
                // the June 2007 mod (shown below as commented-out assignment to address) does.
                // This mod must be made in conjunction with InstructionSet.java's processBranch()
                // method.  There are some comments there as well.

                if (instruction instanceof BasicInstruction) {
                    BasicInstructionFormat format = ((BasicInstruction) instruction).getInstructionFormat();
                    if (format == BasicInstructionFormat.I_BRANCH_FORMAT) {
                        //address = (address - (this.textAddress+((Globals.getSettings().getDelayedBranchingEnabled())? Instruction.INSTRUCTION_LENGTH : 0))) >> 2;
                        address = (address - (this.textAddress + Instruction.INSTRUCTION_LENGTH)) >> 2;
                        absoluteAddress = false;
                    }
                }
                //////////////////////////////////////////////////////////////////////
                basic += address;
                if (absoluteAddress) { // record as address if absolute, value if relative
                    basicStatementList.addAddress(address);
                } else {
                    basicStatementList.addValue(address);
                }
                this.operands[this.numOperands++] = address;
            } else if (tokenType == TokenTypes.INTEGER_5 || tokenType == TokenTypes.INTEGER_16 ||
                    tokenType == TokenTypes.INTEGER_16U || tokenType == TokenTypes.INTEGER_32) {

                int tempNumeric = Binary.stringToInt(tokenValue);

                /***************************************************************************
                 *  MODIFICATION AND COMMENT, DPS 3-July-2008
                 *
                 * The modifications of January 2005 documented below are being rescinded.
                 * All hexadecimal immediate values are considered 32 bits in length and
                 * their classification as INTEGER_5, INTEGER_16, INTEGER_16U (new)
                 * or INTEGER_32 depends on their 32 bit value.  So 0xFFFF will be
                 * equivalent to 0x0000FFFF instead of 0xFFFFFFFF.  This change, along with
                 * the introduction of INTEGER_16U (adopted from Greg Gibeling of Berkeley),
                 * required extensive changes to instruction templates especially for
                 * pseudo-instructions.
                 *
                 * This modification also appears inbuildBasicStatementFromBasicInstruction()
                 * in mars.ProgramStatement.
                 *
                 *  ///// Begin modification 1/4/05 KENV   ///////////////////////////////////////////
                 *  // We have decided to interpret non-signed (no + or -) 16-bit hexadecimal immediate
                 *  // operands as signed values in the range -32768 to 32767. So 0xffff will represent
                 *  // -1, not 65535 (bit 15 as sign bit), 0x8000 will represent -32768 not 32768.
                 *  // NOTE: 32-bit hexadecimal immediate operands whose values fall into this range
                 *  // will be likewise affected, but they are used only in pseudo-instructions.  The
                 *  // code in ExtendedInstruction.java to split this number into upper 16 bits for "lui"
                 *  // and lower 16 bits for "ori" works with the original source code token, so it is
                 *  // not affected by this tweak.  32-bit immediates in data segment directives
                 *  // are also processed elsewhere so are not affected either.
                 *  ////////////////////////////////////////////////////////////////////////////////
                 *
                 *        if (tokenType != TokenTypes.INTEGER_16U) { // part of the Berkeley mod...
                 *           if ( Binary.isHex(tokenValue) &&
                 *             (tempNumeric >= 32768) &&
                 *             (tempNumeric <= 65535) )  // Range 0x8000 ... 0xffff
                 *           {
                 *              // Subtract the 0xffff bias, because strings in the
                 *              // range "0x8000" ... "0xffff" are used to represent
                 *              // 16-bit negative numbers, not positive numbers.
                 *              tempNumeric = tempNumeric - 65536;
                 *              // Note: no action needed for range 0xffff8000 ... 0xffffffff
                 *           }
                 *        }
                 **************************  END DPS 3-July-2008 COMMENTS *******************************/

                basic += tempNumeric;
                basicStatementList.addValue(tempNumeric);
                this.operands[this.numOperands++] = tempNumeric;
                ///// End modification 1/7/05 KENV   ///////////////////////////////////////////
            } else {
                basicStatementElement = tokenValue;
                basic += basicStatementElement;
                basicStatementList.addString(basicStatementElement);
            }
            // add separator if not at end of token list AND neither current nor 
            // next token is a parenthesis
            if ((i < strippedTokenList.size() - 1)) {
                nextTokenType = strippedTokenList.get(i + 1).getType();
                if (tokenType != TokenTypes.LEFT_PAREN && tokenType != TokenTypes.RIGHT_PAREN &&
                        nextTokenType != TokenTypes.LEFT_PAREN && nextTokenType != TokenTypes.RIGHT_PAREN) {
                    basicStatementElement = ",";
                    basic += basicStatementElement;
                    basicStatementList.addString(basicStatementElement);
                }
            }
        }
        this.basicAssemblyStatement = basic;
    } //buildBasicStatementFromBasicInstruction()


    /////////////////////////////////////////////////////////////////////////////

    /**
     * Given the current statement in Basic Assembly format (see above), build the
     * 32-bit binary machine code statement.
     *
     * @param errors The list of assembly errors encountered so far.  May add to it here.
     **/
    public void buildMachineStatementFromBasicStatement(ErrorList errors) {

        try {
            //mask indicates bit positions for 'f'irst, 's'econd, 't'hird operand
            this.machineStatement = ((BasicInstruction) instruction).getOperationMask();
        }   // This means the pseudo-instruction expansion generated another
        // pseudo-instruction (expansion must be to all basic instructions).
        // This is an error on the part of the pseudo-instruction author.
        catch (ClassCastException cce) {
            errors.add(new ErrorMessage(this.sourceMIPSprogram, this.sourceLine, 0,
                    "INTERNAL ERROR: pseudo-instruction expansion contained a pseudo-instruction"));
            return;
        }
        BasicInstructionFormat format = ((BasicInstruction) instruction).getInstructionFormat();

        if (format == BasicInstructionFormat.J_FORMAT) {
            if ((this.textAddress & 0xF0000000) != (this.operands[0] & 0xF0000000)) {
                // attempt to jump beyond 28-bit byte (26-bit word) address range.
                // SPIM flags as warning, I'll flag as error b/c MARS text segment not long enough for it to be OK.
                errors.add(new ErrorMessage(this.sourceMIPSprogram, this.sourceLine, 0,
                        "Jump target word address beyond 26-bit range"));
                return;
            }
            // Note the  bit shift to make this a word address.
            this.operands[0] = this.operands[0] >>> 2;
            this.insertBinaryCode(this.operands[0], Instruction.operandMask[0], errors);
        } else if (format == BasicInstructionFormat.I_BRANCH_FORMAT) {
            for (int i = 0; i < this.numOperands - 1; i++) {
                this.insertBinaryCode(this.operands[i], Instruction.operandMask[i], errors);
            }
            this.insertBinaryCode(operands[this.numOperands - 1], Instruction.operandMask[this.numOperands - 1], errors);
        } else {  // R_FORMAT or I_FORMAT
            for (int i = 0; i < this.numOperands; i++)
                this.insertBinaryCode(this.operands[i], Instruction.operandMask[i], errors);
        }
        this.binaryStatement = Binary.binaryStringToInt(this.machineStatement);
        return;
    } // buildMachineStatementFromBasicStatement(


    /////////////////////////////////////////////////////////////////////////////

    /**
     * Crude attempt at building String representation of this complex structure.
     *
     * @return A String representing the ProgramStatement.
     **/

    public String toString() {
        // a crude attempt at string formatting.  Where's C when you need it?
        String blanks = "                               ";
        String result = "[" + this.textAddress + "]";
        if (this.basicAssemblyStatement != null) {
            int firstSpace = this.basicAssemblyStatement.indexOf(" ");
            result += blanks.substring(0, 16 - result.length()) + this.basicAssemblyStatement.substring(0, firstSpace);
            result += blanks.substring(0, 24 - result.length()) + this.basicAssemblyStatement.substring(firstSpace + 1);
            ;
        } else {
            result += blanks.substring(0, 16 - result.length()) + "0x" + Integer.toString(this.binaryStatement, 16);
        }
        result += blanks.substring(0, 40 - result.length()) + ";  "; // this.source;
        if (operands != null) {
            for (int i = 0; i < this.numOperands; i++)
                // result += operands[i] + " ";
                result += Integer.toString(operands[i], 16) + " ";
        }
        if (this.machineStatement != null) {
            result += "[" + Binary.binaryStringToHexString(this.machineStatement) + "]";
            result += "  " + this.machineStatement.substring(0, 6) + "|" + this.machineStatement.substring(6, 11) + "|" +
                    this.machineStatement.substring(11, 16) + "|" + this.machineStatement.substring(16, 21) + "|" +
                    this.machineStatement.substring(21, 26) + "|" + this.machineStatement.substring(26, 32);
        }
        return result;
    } // toString()

    /**
     * Assigns given String to be Basic Assembly statement equivalent to this source line.
     *
     * @param statement A String containing equivalent Basic Assembly statement.
     **/

    public void setBasicAssemblyStatement(String statement) {
        basicAssemblyStatement = statement;
    }

    /**
     * Assigns given String to be binary machine code (32 characters, all of them 0 or 1)
     * equivalent to this source line.
     *
     * @param statement A String containing equivalent machine code.
     **/

    public void setMachineStatement(String statement) {
        machineStatement = statement;
    }

    /**
     * Assigns given int to be binary machine code equivalent to this source line.
     *
     * @param binaryCode An int containing equivalent binary machine code.
     **/

    public void setBinaryStatement(int binaryCode) {
        binaryStatement = binaryCode;
    }


    /**
     * associates MIPS source statement.  Used by assembler when generating basic
     * statements during macro expansion of extended statement.
     *
     * @param src a MIPS source statement.
     **/

    public void setSource(String src) {
        source = src;
    }


    /**
     * Produces MIPSprogram object representing the source file containing this statement.
     *
     * @return The MIPSprogram object.  May be null...
     **/
    public MIPSprogram getSourceMIPSprogram() {
        return sourceMIPSprogram;
    }

    /**
     * Produces String name of the source file containing this statement.
     *
     * @return The file name.
     **/
    public String getSourceFile() {
        return (sourceMIPSprogram == null) ? "" : sourceMIPSprogram.getFilename();
    }


    /**
     * Produces MIPS source statement.
     *
     * @return The MIPS source statement.
     **/

    public String getSource() {
        return source;
    }

    /**
     * Produces line number of MIPS source statement.
     *
     * @return The MIPS source statement line number.
     **/

    public int getSourceLine() {
        return sourceLine;
    }

    /**
     * Produces Basic Assembly statement for this MIPS source statement.
     * All numeric values are in decimal.
     *
     * @return The Basic Assembly statement.
     **/

    public String getBasicAssemblyStatement() {
        return basicAssemblyStatement;
    }

    /**
     * Produces printable Basic Assembly statement for this MIPS source
     * statement.  This is generated dynamically and any addresses and
     * values will be rendered in hex or decimal depending on the current
     * setting.
     *
     * @return The Basic Assembly statement.
     **/
    public String getPrintableBasicAssemblyStatement() {
        return basicStatementList.toString();
    }

    /**
     * Produces binary machine statement as 32 character string, all '0' and '1' chars.
     *
     * @return The String version of 32-bit binary machine code.
     **/

    public String getMachineStatement() {
        return machineStatement;
    }

    /**
     * Produces 32-bit binary machine statement as int.
     *
     * @return The int version of 32-bit binary machine code.
     **/
    public int getBinaryStatement() {
        return binaryStatement;
    }

    /**
     * Produces token list generated from original source statement.
     *
     * @return The TokenList of Token objects generated from original source.
     **/
    public TokenList getOriginalTokenList() {
        return originalTokenList;
    }

    /**
     * Produces token list stripped of all but operator and operand tokens.
     *
     * @return The TokenList of Token objects generated by stripping original list of all
     * except operator and operand tokens.
     **/
    public TokenList getStrippedTokenList() {
        return strippedTokenList;
    }

    /**
     * Produces Instruction object corresponding to this statement's operator.
     *
     * @return The Instruction that matches the operator used in this statement.
     **/
    public Instruction getInstruction() {
        return instruction;
    }

    /**
     * Produces Text Segment address where the binary machine statement is stored.
     *
     * @return address in Text Segment of this binary machine statement.
     **/
    public int getAddress() {
        return textAddress;
    }

    /**
     * Produces int array of operand values for this statement.
     *
     * @return int array of operand values (if any) required by this statement's operator.
     **/
    public int[] getOperands() {
        return operands;
    }

    /**
     * Produces operand value from given array position (first operand is position 0).
     *
     * @param i Operand position in array (first operand is position 0).
     * @return Operand value at given operand array position.  If < 0 or >= numOperands, it returns -1.
     **/
    public int getOperand(int i) {
        if (i >= 0 && i < this.numOperands) {
            return operands[i];
        } else {
            return -1;
        }
    }


    //////////////////////////////////////////////////////////////////////////////
    //  Given operand (register or integer) and mask character ('f', 's', or 't'),
    //  generate the correct sequence of bits and replace the mask with them.
    private void insertBinaryCode(int value, char mask, ErrorList errors) {
        int startPos = this.machineStatement.indexOf(mask);
        int endPos = this.machineStatement.lastIndexOf(mask);
        if (startPos == -1 || endPos == -1) { // should NEVER occur
            errors.add(new ErrorMessage(this.sourceMIPSprogram, this.sourceLine, 0,
                    "INTERNAL ERROR: mismatch in number of operands in statement vs mask"));
            return;
        }
        String bitString = Binary.intToBinaryString(value, endPos - startPos + 1);
        String state = this.machineStatement.substring(0, startPos) + bitString;
        if (endPos < this.machineStatement.length() - 1)
            state = state + this.machineStatement.substring(endPos + 1);
        this.machineStatement = state;
        return;
    } // insertBinaryCode()


    //////////////////////////////////////////////////////////////////////////////
    /*
     *   Given a model BasicInstruction and the assembled (not source) operand array for a statement,
     *   this method will construct the corresponding basic instruction list.  This method is
     *   used by the constructor that is given only the int address and binary code.  It is not
     *   intended to be used when source code is available.  DPS 11-July-2013
     */
    private BasicStatementList buildBasicStatementListFromBinaryCode(int binary, BasicInstruction instr, int[] operands, int numOperands) {
        BasicStatementList statementList = new BasicStatementList();
        int tokenListCounter = 1;  // index 0 is operator; operands start at index 1
        if (instr == null) {
            statementList.addString(invalidOperator);
            return statementList;
        } else {
            statementList.addString(instr.getName() + " ");
        }
        for (int i = 0; i < numOperands; i++) {
            // add separator if not at end of token list AND neither current nor 
            // next token is a parenthesis
            if (tokenListCounter > 1 && tokenListCounter < instr.getTokenList().size()) {
                TokenTypes thisTokenType = instr.getTokenList().get(tokenListCounter).getType();
                if (thisTokenType != TokenTypes.LEFT_PAREN && thisTokenType != TokenTypes.RIGHT_PAREN) {
                    statementList.addString(",");
                }
            }
            boolean notOperand = true;
            while (notOperand && tokenListCounter < instr.getTokenList().size()) {
                TokenTypes tokenType = instr.getTokenList().get(tokenListCounter).getType();
                if (tokenType.equals(TokenTypes.LEFT_PAREN)) {
                    statementList.addString("(");
                } else if (tokenType.equals(TokenTypes.RIGHT_PAREN)) {
                    statementList.addString(")");
                } else if (tokenType.toString().contains("REGISTER")) {
                    String marker = (tokenType.toString().contains("FP_REGISTER")) ? "$f" : "$";
                    statementList.addString(marker + operands[i]);
                    notOperand = false;
                } else {
                    statementList.addValue(operands[i]);
                    notOperand = false;
                }
                tokenListCounter++;
            }
        }
        while (tokenListCounter < instr.getTokenList().size()) {
            TokenTypes tokenType = instr.getTokenList().get(tokenListCounter).getType();
            if (tokenType.equals(TokenTypes.LEFT_PAREN)) {
                statementList.addString("(");
            } else if (tokenType.equals(TokenTypes.RIGHT_PAREN)) {
                statementList.addString(")");
            }
            tokenListCounter++;
        }
        return statementList;
    } // buildBasicStatementListFromBinaryCode()


    //////////////////////////////////////////////////////////
    //
    //  Little class to represent basic statement as list
    //  of elements.  Each element is either a string, an
    //  address or a value.  The toString() method will
    //  return a string representation of the basic statement
    //  in which any addresses or values are rendered in the
    //  current number format (e.g. decimal or hex).
    //
    //  NOTE: Address operands on Branch instructions are
    //  considered values instead of addresses because they
    //  are relative to the PC.
    //
    //  DPS 29-July-2010

    private class BasicStatementList {

        private ArrayList list;

        BasicStatementList() {
            list = new ArrayList();
        }

        void addString(String string) {
            list.add(new ListElement(0, string, 0));
        }

        void addAddress(int address) {
            list.add(new ListElement(1, null, address));
        }

        void addValue(int value) {
            list.add(new ListElement(2, null, value));
        }

        public String toString() {
            int addressBase = (Globals.getSettings().getBooleanSetting(Settings.DISPLAY_ADDRESSES_IN_HEX)) ? mars.venus.NumberDisplayBaseChooser.HEXADECIMAL : mars.venus.NumberDisplayBaseChooser.DECIMAL;
            int valueBase = (Globals.getSettings().getBooleanSetting(Settings.DISPLAY_VALUES_IN_HEX)) ? mars.venus.NumberDisplayBaseChooser.HEXADECIMAL : mars.venus.NumberDisplayBaseChooser.DECIMAL;

            StringBuffer result = new StringBuffer();
            for (int i = 0; i < list.size(); i++) {
                ListElement e = (ListElement) list.get(i);
                switch (e.type) {
                    case 0:
                        result.append(e.sValue);
                        break;
                    case 1:
                        result.append(mars.venus.NumberDisplayBaseChooser.formatNumber(e.iValue, addressBase));
                        break;
                    case 2:
                        if (valueBase == mars.venus.NumberDisplayBaseChooser.HEXADECIMAL) {
                            result.append(mars.util.Binary.intToHexString(e.iValue)); // 13-July-2011, was: intToHalfHexString()
                        } else {
                            result.append(mars.venus.NumberDisplayBaseChooser.formatNumber(e.iValue, valueBase));
                        }
                    default:
                        break;
                }
            }
            return result.toString();
        }

        private class ListElement {
            int type;
            String sValue;
            int iValue;

            ListElement(int type, String sValue, int iValue) {
                this.type = type;
                this.sValue = sValue;
                this.iValue = iValue;
            }
        }
    }

}
