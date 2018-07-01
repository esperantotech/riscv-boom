//******************************************************************************
// Copyright (c) 2017, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Set-associative Branch Target Buffer with RAS and BIM predictor (BTB-sa)
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Stages:
//    * S0 -- receive address to predict on
//    * S1 -- perform lookup
//    * S2 -- return our prediction
//
// A predicted-taken will insert 1 bubble into the pipeline.
//
// TODO:
//    - provide way to clear/reset BTB.
//
// NOTES:
//    - No compression of high-order tag bits or target bits.
//    - We store the full targets, instead of just the branch/jump offsets.
//    - Only performs partial tag matches -- must verify elsewhere that target was valid.
//    - BTB is allowed to be stale (Debug Program Buffer and other self-modifying code may end up here).

package boom.bpu

import Chisel._
import freechips.rocketchip.config.Parameters
import boom.common._
import boom.exu._

import freechips.rocketchip.util.Str

// Set-associative branch target buffer.
class BTBsa(implicit p: Parameters) extends BoomBTB
{

   val bim = Module(new BimodalTable())
   bim.io.req := io.req
   bim.io.do_reset := false.B // TODO
   bim.io.flush := false.B // TODO
   bim.io.update := io.bim_update


   private val lsb_sz = log2Up(coreInstBytes)
   private def getTag (addr: UInt): UInt = addr(tag_sz+idx_sz+lsb_sz-1, idx_sz+lsb_sz)
   private def getIdx (addr: UInt): UInt = addr(idx_sz+lsb_sz-1, lsb_sz)

   class BTBSetData extends Bundle
   {
      val target = UInt(width = vaddrBits - log2Up(coreInstBytes))
      val cfi_idx = UInt(width = log2Up(2*fetchWidth))
      val bpd_type = BpredType()
      val cfi_type = CfiType()
   }


   val stall = !io.req.valid
   val s0_idx = getIdx(io.req.bits.addr)(idx_sz-1,0)
   val s1_idx = RegEnable(s0_idx, !stall)

   // prediction
   val s1_valid = Wire(Bool())
   val s1_resp_bits = Wire(new BoomBTBResp)
   val hits_oh = Wire(Vec(nWays, Bool()))
   val data_out = Wire(Vec(nWays, new BTBSetData()))
   val s1_req_tag = RegEnable(getTag(io.req.bits.addr), !stall)

   // updates
   val r_btb_update = Pipe(io.btb_update)
   val update_valid = r_btb_update.valid && !io.status_debug
   val widx = getIdx(r_btb_update.bits.pc)
   val wtag = getTag(r_btb_update.bits.pc)
   // TODO: currently a not-very-clever way to choose a replacement way.
   val next_replace = Counter(r_btb_update.valid, nWays)._1
   val way_wen = UIntToOH(next_replace)

   // clear entries (e.g., multiple tag hits, which is an invalid variant)
   val clear_valid = Wire(init=false.B)
   val clear_idx = s1_idx


   for (w <- 0 until nWays)
   {
      val wen = update_valid && way_wen(w)

      val valids   = Reg(init = UInt(0, nSets))
      val tags     = SeqMem(nSets, UInt(width = tag_sz))
      val data     = SeqMem(nSets, new BTBSetData())

      tags.suggestName("btb_tag_array")
      data.suggestName("btb_data_array")

      val is_valid = (valids >> s1_idx)(0) && RegNext(!wen)
      val rout     = data.read(s0_idx, !wen)
      val rtag     = tags.read(s0_idx, !wen)
      hits_oh(w)   := is_valid && (rtag === s1_req_tag)
      data_out(w)  := rout

      when (wen)
      {
         valids := valids.bitSet(widx, true.B)

         val newdata = Wire(new BTBSetData())
         newdata.target  := r_btb_update.bits.target(vaddrBits-1, log2Up(coreInstBytes))
         newdata.cfi_idx := r_btb_update.bits.cfi_pc >> log2Up(coreInstBytes)
         newdata.bpd_type := r_btb_update.bits.bpd_type
         newdata.cfi_type := r_btb_update.bits.cfi_type

         tags(widx) := wtag
         data(widx) := newdata
      }

      when (clear_valid)
      {
         valids := valids.bitSet(clear_idx, false.B)
      }

      if (DEBUG_PRINTF)
      {
         //printf("BTB write (%c): %d 0x%x (PC= 0x%x, TARG= 0x%x) way=%d C=%d\n", Mux(wen, Str("w"), Str("-")), widx,
         //wtag, r_btb_update.bits.pc, r_btb_update.bits.target, UInt(w), clear_valid)
         //for (i <- 0 until nSets)
         //{
         //   printf("    [%d] %d tag=0x%x targ=0x%x [0x%x 0x%x]\n", UInt(i), (valids >> UInt(i))(0),
         //   tags.read(UInt(i)),
         //   data.read(UInt(i)).target,
         //   tags.read(UInt(i)) << UInt(idx_sz + log2Up(fetchWidth*coreInstBytes)),
         //   data.read(UInt(i)).target << log2Up(coreInstBytes)
         //   )
         //}
      }
   }

   // Zap entries if multiple hits.
   when (freechips.rocketchip.util.PopCountAtLeast(hits_oh.asUInt, 2))
   {
      clear_valid := true.B
   }


   // Mux out the winning hit.
   s1_valid := PopCount(hits_oh) === UInt(1) && !io.flush
   val s1_data = Mux1H(hits_oh, data_out)
   val s1_target = Cat(s1_data.target, UInt(0, log2Up(coreInstBytes)))
   val s1_cfi_idx = s1_data.cfi_idx
   val s1_bpd_type = s1_data.bpd_type
   val s1_cfi_type = s1_data.cfi_type


   s1_resp_bits.target := s1_target
   s1_resp_bits.cfi_idx := (if (fetchWidth > 1) s1_cfi_idx else 0.U)
   s1_resp_bits.bpd_type := s1_bpd_type
   s1_resp_bits.cfi_type := s1_cfi_type
//   s1_resp_bits.mask := Cat((1.U << ~Mux(s1_resp_bits.taken, ~s1_resp_bits.cfi_idx, 0.U))-1.U, 1.U)

   val s0_pc = Wire(UInt(width=vaddrBits))
   val s1_pc = RegEnable(io.req.bits.addr, !stall)
   s1_resp_bits.fetch_pc := s1_pc

   if (nRAS > 0)
   {
      val ras = new RAS(nRAS, coreInstBytes, vaddrBits)
      val doPeek = (hits_oh zip data_out map {case(hit, d) => hit && BpredType.isReturn(d.bpd_type)}).reduce(_||_)
      val isEmpty = if (rasCheckForEmpty) ras.isEmpty else false.B
      when (!isEmpty && doPeek)
      {
         s1_resp_bits.target := ras.peek
      }

      when (io.ras_update.valid)
      {
         when (io.ras_update.bits.is_call)
         {
            ras.push(io.ras_update.bits.return_addr)
            if (bypassCalls)
            {
               // bypassing couples ras_update.valid to the critical path.
               when (doPeek)
               {
                  s1_resp_bits.target := io.ras_update.bits.return_addr
               }
            }
         }
         .elsewhen (io.ras_update.bits.is_ret) // only pop if BTB hit!
         {
            ras.pop()
         }
      }
   }


   //************************************************
   // Output.

   io.resp.valid := RegNext(s1_valid)
   io.resp.bits := RegNext(s1_resp_bits)

   io.resp.bits.bim_resp := bim.io.resp

   // Does the BIM think we should take it?
   io.resp.bits.taken :=
      (bim.io.resp.valid && bim.io.resp.bits.isTaken(io.resp.bits.cfi_idx)) ||
      RegNext(BpredType.isAlwaysTaken(s1_bpd_type))
   io.resp.bits.mask := Cat((1.U << ~Mux(io.resp.bits.taken, ~io.resp.bits.cfi_idx, 0.U))-1.U, 1.U)


   //************************************************
   // Debug.

   if (DEBUG_PRINTF)
   {
      printf("BTB predi (%c): hits:%x %d (PC= 0x%x, TARG= 0x%x %d) s2_BIM [%d %d 0x%x]\n",
         Mux(s1_valid, Str("V"), Str("-")), hits_oh.asUInt, true.B, RegNext(io.req.bits.addr), s1_target, s1_cfi_type,
         bim.io.resp.valid, bim.io.resp.bits.entry_idx, bim.io.resp.bits.rowdata)
   }

   override def toString: String =
      "\n   ==BTB==" +
      "\n   Sets          : " + nSets +
      "\n   Ways          : " + nWays +
      "\n   Tag Size      : " + tag_sz + "\n" +
      bim.toString
}

