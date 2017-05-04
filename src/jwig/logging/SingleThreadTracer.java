/*
 * Copyright (c) 2017, University of California, Berkeley
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package jwig.logging;

import janala.logger.inst.*;
import jwig.util.DoublyLinkedList;
import jwig.util.Stack;
import jwig.util.SyncBlockingDeque;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * This class is responsible for data-trace logging for an instruction stream
 * generated by a single thread in the application.
 *
 * @author Rohan Padhye
 */
class SingleThreadTracer extends Thread {
    private final SyncBlockingDeque<Instruction> queue = new SyncBlockingDeque<>();
    private final PrintLogger logger;
    private final Thread tracee;
    private final Stack<Callable<?>> handlers = new DoublyLinkedList<>();

    /** Creates a new tracer that will print the data-traces of a tracee to a logger. */
    protected SingleThreadTracer(Thread tracee, PrintLogger logger) {
        super("__JWIG_TRACER__"); // The name is important to block snooping
        this.tracee = tracee;
        this.logger = logger;
        this.handlers.push(new BaseHandler());
    }

    /** Spawns a thread tracer for the current thread and returns its reference. */
    protected static SingleThreadTracer spawn(PrintLogger logger) {
        SingleThreadTracer t = new SingleThreadTracer(Thread.currentThread(), logger);
        t.start();
        return t;
    }

    /** Sends an instruction to the tracer for processing. */
    protected void consume(Instruction ins) {
        try {
            queue.putLast(ins);
        } catch (InterruptedException e) {
            this.interrupt(); // This is a bad sign
        }
    }

    /**
     * Retrieves the next yet-unprocessed instruction in FIFO sequeuence.
     *
     * This method blocks for the next instruction up to a fixed timeout.
     * After the timeout, it checks to see if the tracee is alive and if so
     * repeats the timed-block. If the tracee is dead, the tracer is
     * interrupted.
     *
     * */
    protected Instruction next() throws InterruptedException {
        // If a restored instruction exists, take that out instead of polling the queue
        if (restored != null) {
            Instruction ins = restored;
            restored = null;
            return ins;
        }
        // Keep attempting to get instructions while queue is non-empty or tracee is alive
        while (!queue.isEmpty() || tracee.isAlive()) {
            // Attempt to poll queue with a timeout
            Instruction ins = queue.pollFirst(1, TimeUnit.SECONDS);
            // Return instruction if available, else re-try
            if (ins != null) {
                return ins;
            }
        }
        // If tracee is dead, interrupt this thread
        throw new InterruptedException();
    }

    // Hack on restore() to prevent deadlocks when main thread waits on put() and logger on restore()
    private Instruction restored = null;
    /** Returns an instruction to the queue for processing (used by lookaheads). */
    protected void restore(Instruction ins) {
        if (restored != null) {
            throw new IllegalStateException("Cannot restore multiple instructions");
        } else {
            restored = ins;
        }
    }

    @Override
    public void run() {
        try {
            while (!handlers.isEmpty()) {
                handlers.peek().call();
            }
        } catch (InterruptedException e) {
            // Exit normally
        } catch (Throwable e) {
            e.printStackTrace(logger.getWriter());
            handlers.clear(); // Don't do anything else
        } finally {
            logger.close();
        }
    }

    private static boolean isReturnOrMethodThrow(Instruction inst) {
        return  inst instanceof ARETURN ||
                inst instanceof LRETURN ||
                inst instanceof DRETURN ||
                inst instanceof FRETURN ||
                inst instanceof IRETURN ||
                inst instanceof RETURN  ||
                inst instanceof METHOD_THROW;
    }


    private static boolean isInvoke(Instruction inst) {
        return  inst instanceof INVOKEINTERFACE ||
                inst instanceof INVOKESPECIAL  ||
                inst instanceof INVOKESTATIC   ||
                inst instanceof INVOKEVIRTUAL;
    }

    private static boolean isIfJmp(Instruction inst) {
        return  inst instanceof IF_ACMPEQ ||
                inst instanceof IF_ACMPNE ||
                inst instanceof IF_ICMPEQ ||
                inst instanceof IF_ICMPNE ||
                inst instanceof IF_ICMPGT ||
                inst instanceof IF_ICMPGE ||
                inst instanceof IF_ICMPLT ||
                inst instanceof IF_ICMPLE ||
                inst instanceof IFEQ ||
                inst instanceof IFNE ||
                inst instanceof IFGT ||
                inst instanceof IFGE ||
                inst instanceof IFLT ||
                inst instanceof IFLE ||
                inst instanceof IFNULL ||
                inst instanceof IFNONNULL;

    }

    private static String getInvocationTarget(Instruction invokeIns) {
        if (invokeIns instanceof INVOKESPECIAL) {
            return getNameDesc((INVOKESPECIAL) invokeIns);
        } else if (invokeIns instanceof INVOKEINTERFACE) {
            return getNameDesc((INVOKEINTERFACE) invokeIns);
        } else if (invokeIns instanceof INVOKEVIRTUAL) {
            return getNameDesc((INVOKEVIRTUAL) invokeIns);
        } else if (invokeIns instanceof INVOKESTATIC) {
            return getNameDesc((INVOKESTATIC) invokeIns);
        } else {
            throw new IllegalArgumentException("Not an invoke instruction: " + invokeIns);
        }
    }

    private static String getOwnerNameDesc(MemberRef mr) {
        return mr.getOwner() + "#" + mr.getName() + mr.getDesc();
    }

    private static String getNameDesc(MemberRef mr) {
        return mr.getName() + mr.getDesc();
    }


    class BaseHandler implements Callable<Void> {
        @Override
        public Void call() throws Exception {
            Instruction ins = next();
            if (ins instanceof METHOD_BEGIN) {
                METHOD_BEGIN begin = (METHOD_BEGIN) ins;
                if (getNameDesc(begin).equals("main([Ljava/lang/String;)V")) {
                    handlers.pop();
                    handlers.push(new TravioliHandler(begin, 0));
                }  else if (getNameDesc(begin).equals("run()V")) {
                    handlers.pop();
                    handlers.push(new TravioliHandler(begin, 0));
                } else {
                    // Ignore all non-main or non-run top-level calls in this thread
                    handlers.push(new MatchingNullHandler());
                }
            } else {
                // Instructions not nested in a METHOD_BEGIN are quite unexpected
                System.err.println("Unexpected: " + ins);
            }
            return null;
        }
    }

    class TravioliHandler implements Callable<Void> {

        private final int depth;
        private final String methodDesc;
        TravioliHandler(METHOD_BEGIN begin, int depth) {
            this.depth = depth;
            this.methodDesc = getNameDesc(begin);
            logger.log(tabs() + "BEGIN "+getOwnerNameDesc(begin));
            //logger.log(tabs() + begin);
        }

        private String tabs() {
            StringBuffer sb = new StringBuffer(depth);
            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
            return sb.toString();
        }

        private String invokeTarget = null;
        private boolean invokingSuperOrThis = false;
        private int lastIid = 0;
        private int lastMid = 0;


        @Override
        public Void call() throws InterruptedException {
            Instruction ins = next();

            if (ins instanceof METHOD_BEGIN) {
                METHOD_BEGIN begin = (METHOD_BEGIN) ins;
                String beginNameDesc = getNameDesc(begin);

                if (beginNameDesc.equals(this.invokeTarget)) {
                    // Trace continues with callee
                    logger.log(tabs() + "CALL("+lastIid+","+lastMid+")");
                    handlers.push(new TravioliHandler(begin, depth+1));
                } else {
                    // Class loading or static initializer
                    handlers.push(new MatchingNullHandler());
                }
            } else {


                // This should never really happen:
                if (ins instanceof INVOKEMETHOD_EXCEPTION &&
                        (this.invokeTarget == null))  {
                    throw new RuntimeException("Unexpected INVOKEMETHOD_EXCEPTION");
                }
                // This should never really happen:
                if (ins instanceof INVOKEMETHOD_END && this.invokeTarget == null) {
                    throw new RuntimeException("Unexpected INVOKEMETHOD_END");
                }

                // Do not log SPECIAL instructions if they haven't been consumed by their predecessors
                if (ins instanceof SPECIAL) {
                    SPECIAL special = (SPECIAL) ins;
                    // Handle marker that says calling super() or this()
                    if (special.i == SPECIAL.CALLING_SUPER_OR_THIS) {
                        this.invokingSuperOrThis = true;
                    }

                    return null; // Do not process SPECIAL instructions further
                }



                // Handle setting or un-setting of invokeTarget buffer
                if (isInvoke(ins)) {
                    // Remember invocation target until METHOD_BEGIN or INVOKEMETHOD_END/INVOKEMETHOD_EXCEPTION
                    String targetNameDesc = getInvocationTarget(ins);
                    this.invokeTarget = targetNameDesc;
                } else if (this.invokeTarget != null) {
                    // If we don't step into a method call, we must be stepping over it
                    assert(ins instanceof  INVOKEMETHOD_END || ins instanceof  INVOKEMETHOD_EXCEPTION);

                    // Unset the invocation target for the rest of the instruction stream
                    this.invokeTarget = null;

                    // Handle end of super() or this() call
                    if (invokingSuperOrThis) {
                        if (ins instanceof INVOKEMETHOD_END) {
                            // For normal end, simply unset the flag
                            this.invokingSuperOrThis = false;
                        } else {
                            assert(ins instanceof  INVOKEMETHOD_EXCEPTION);

                            while (true) { // will break when outer caller of <init> found
                                logger.log(tabs() + "RET");
                                handlers.pop();
                                Callable<?> handler = handlers.peek();
                                // We should not reach the BaseHandler without finding
                                // the TravioliHandler who called the outer <init>().
                                assert (handler instanceof TravioliHandler);
                                TravioliHandler travioliHandler = (TravioliHandler) handler;
                                if (travioliHandler.invokingSuperOrThis) {
                                    // Go down the stack further
                                    continue;
                                } else {
                                    // Found caller of new()
                                    assert(travioliHandler.invokeTarget.startsWith("<init>"));
                                    restore(ins);
                                    return null; // defer handling to new top of stack
                                }
                            }

                        }
                    }

                }


                // Log conditional branches
                if (isIfJmp(ins)) {
                    Instruction next = next();
                    int branchId;
                    int lineNum = ins.mid;
                    if ((next instanceof SPECIAL) && ((SPECIAL) next).i == SPECIAL.DID_NOT_BRANCH) {
                        // Special marker ==> False Branch
                        branchId = -ins.iid;
                    } else {
                        // Not a special marker ==> True Branch
                        restore(next); // Remember to put this instruction back on the queue
                        branchId = ins.iid;
                    }
                    logger.log(tabs() + "BRANCH("+branchId+","+lineNum+")");
                }

                // Log memory access instructions
                if (ins instanceof HEAPLOAD) {
                    HEAPLOAD heapload = (HEAPLOAD) ins;
                    int iid = heapload.iid;
                    int lineNum = heapload.mid;
                    int objectId = heapload.objectId;
                    String field = heapload.field;
                    // Log the object access (unless it was a NPE)
                    if (objectId != 0) {
                        logger.log(tabs() + String.format("HEAPLOAD(%d,%d,%d,%s)", iid, lineNum, objectId, field));
                    }
                }


                if (isReturnOrMethodThrow(ins)) {
                    logger.log(tabs() + "RET");
                    handlers.pop();
                }

                // For non-METHOD_BEGIN instructions, set last IID and lineNum
                this.lastIid = ins.iid;
                this.lastMid = ins.mid;

            }


            return null;
        }
    }

    class NullHandler implements Callable<Void> {
        @Override
        public Void call() throws InterruptedException {
            next();
            return null;
        }
    }


    class MatchingNullHandler implements Callable<Void> {
        @Override
        public Void call() throws InterruptedException {
            Instruction ins = next();
            if (ins instanceof METHOD_BEGIN) {
                handlers.push(new MatchingNullHandler());
            } else if (isReturnOrMethodThrow(ins)) {
                handlers.pop();
            }
            return null;
        }
    }
}
