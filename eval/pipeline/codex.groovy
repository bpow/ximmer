// vim: ts=4:expandtab:sw=4:cindent
//////////////////////////////////////////////////////////////////
// 
// Pipeline stage to run CODEX on exome data
//
//////////////////////////////////////////////////////////////////
codex_call_cnvs = {

    var batch_name : false,
        k_offset : 0,
        max_k : 9

    def chr = branch.name
    
    if(!(chr in analysable_chromosomes))
        succeed "Chromosome $chr is not analysable by CODEX"
    
    def outputFile = batch_name ? batch_name + '.codex.' + chr + '.cnvs.tsv' : input.bam + '.codex.' + chr + '.cnvs.tsv'

    produce(outputFile) {
        
        def bamDir = file(input.bam).parentFile.absoluteFile.absolutePath
        
        R({"""

            source("$TOOLS/r-utils/cnv_utils.R")

            library(CODEX)

            bam.files = c('${inputs.bam.join("','")}')

            bam.samples = as.matrix(data.frame(sample=sapply(bam.files, function(file.name) {
                # Scan the bam header and parse out the sample name from the first read group
                read.group.info = strsplit(scanBamHeader(file.name)[[1]]$text[["@RG"]],":")
                names(read.group.info) = sapply(read.group.info, function(field) field[[1]]) 
                return(read.group.info$SM[[2]])
            })))

            bedFile <- "$input.bed"
            chr <- "$chr"

            bambedObj <- getbambed(bamdir = bam.files, bedFile = bedFile,
                                   sampname = bam.samples, 
                                   projectname = "ximmer", chr)
            
            bamdir <- bambedObj$bamdir; sampname <- bambedObj$sampname
            
            
            ref <- bambedObj$ref; 
            projectname <- bambedObj$projectname; 
            chr <- bambedObj\$chr
            
            
            coverageObj <- getcoverage(bambedObj, mapqthres = 20)
            
            Y <- coverageObj$Y; readlength <- coverageObj$readlength
            
            gc <- getgc(chr, ref)
            
            mapp <- getmapp(chr, ref)
            
            qcObj <- qc(Y, sampname, chr, ref, mapp, gc, cov_thresh = c(20, 4000),
                        length_thresh = c(20, 2000), 
                        mapp_thresh = 0.9, 
                        gc_thresh = c(20, 80))
            
            Y_qc <- qcObj$Y_qc; 
            sampname_qc <- qcObj$sampname_qc; 
            gc_qc <- qcObj$gc_qc
            
            mapp_qc <- qcObj$mapp_qc; ref_qc <- qcObj$ref_qc; qcmat <- qcObj$qcmat
            
            normObj <- normalize(Y_qc, gc_qc, K = 1:$max_k)
            
            Yhat <- normObj$Yhat; AIC <- normObj$AIC; BIC <- normObj$BIC

            RSS <- normObj$RSS; 
            K <- normObj$K 
            
            optK = min(9,max(1,K[which.max(BIC)] + $k_offset))
            
            finalcall <- segment(Y_qc, Yhat, optK = optK, K = K, sampname_qc,
                                 ref_qc, chr, lmax = 200, mode = "integer")

            write.table(finalcall,
                        file="$output.tsv",
                        row.names=F,
                        col.names=T,
                        quote=F,
                        sep="\\t"
                        )

        """}, "codex")
    }
    branch.caller_result = output.tsv
}

merge_codex = {
    transform('.chr*.cnvs.tsv','.merge.tsv') {
        exec """
            head -1 $input.tsv >> $output.tsv; 

            for i in $inputs.tsv; do echo "Merge $i"; grep -v '^sample_name' $i  >> $output.tsv || true ; done

            echo "Done."
        """
        
        branch.caller_result = output.tsv
    }
    
}

codex_pipeline = segment {
    chromosomes * [ codex_call_cnvs ] + merge_codex
}
