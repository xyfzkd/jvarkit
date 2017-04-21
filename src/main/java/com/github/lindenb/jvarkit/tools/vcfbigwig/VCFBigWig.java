/*
The MIT License (MIT)

Copyright (c) 2014 Pierre Lindenbaum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


History:
* 2014 creation
* 2015 moved to htsjdk + knime

*/
package com.github.lindenb.jvarkit.tools.vcfbigwig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.broad.igv.bbfile.BBFileReader;
import org.broad.igv.bbfile.BigWigIterator;
import org.broad.igv.bbfile.WigItem;

import com.beust.jcommander.Parameter;
import com.github.lindenb.jvarkit.util.jcommander.Launcher;
import com.github.lindenb.jvarkit.util.jcommander.Program;
import com.github.lindenb.jvarkit.util.log.Logger;
import com.github.lindenb.jvarkit.util.picard.SAMSequenceDictionaryProgress;

import htsjdk.samtools.util.CloserUtil;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLineCount;
import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;

import com.github.lindenb.jvarkit.util.vcf.VcfIterator;


@Program(name="vcfbigwig",description="annotate a VCF with values from a bigwig file")
public class VCFBigWig extends Launcher
	{

	private static final Logger LOG = Logger.build(VCFBigWig.class).make();

	@Parameter(names={"-o","--output"},description="Output file. Optional . Default: stdout")
	private File outputFile = null;


	@Parameter(names={"-B","--bigwig"},description="Path to the bigwig file")
	private String biwWigFile = null;

	@Parameter(names={"-T","--tag"},description="Name of the INFO tag. default: name of the bigwig")
	private String TAG = null;

	@Parameter(names={"-C","--contained"},description="Specifies wig values must be contained by region. if false: return any intersecting region values")
	private boolean contained = false;

	@Parameter(names={"-a","--aggregate"},description="How to aggregate overlapping values: 'avg' average; 'median': median, 'first': use first, 'all' : print all the data")
	private String aggregateMethodStr = "avg";

	@Parameter(names={"-t","--transform"},description="Transform variant chromosome name to match bigwig file. can be 'identity':no change, 'ensembl2ucsc':remove chr prefix")
	private String convertChrName = "identity";

	private enum AggregateMethod
		{
		avg,median,first,all
		}
	private BBFileReader bbFileReader=null;
	private AggregateMethod aggregateMethod=AggregateMethod.avg;
	private Function<String,String> variantChromNameConverter= S -> S ;
	public VCFBigWig()
		{
		}
	
	
	
	 
	@Override
	protected int doVcfToVcf(String inputName, VcfIterator r, VariantContextWriter w) {
		try {
		if(this.aggregateMethodStr.isEmpty()) 
			{
			this.aggregateMethod = AggregateMethod.avg;
			}
		else
			{
			try {
				this.aggregateMethod = AggregateMethod.valueOf(this.aggregateMethodStr);
			} catch(final Exception err)
				{
				LOG.error("Bad value for aggregateMethod must be one of "+Arrays.toString(AggregateMethod.values()));
				return -1;
				}
			}
		final VCFHeader header=r.getHeader();
		final VCFHeader h2=new VCFHeader(header);
		
		if(this.aggregateMethod.equals(AggregateMethod.all))
			{
			h2.addMetaDataLine(new VCFInfoHeaderLine(
					this.TAG,
					VCFHeaderLineCount.UNBOUNDED,
					VCFHeaderLineType.Float,
					"Values from bigwig file: "+this.biwWigFile
					));
			}
		else
			{
			h2.addMetaDataLine(new VCFInfoHeaderLine(
					this.TAG,1,
					VCFHeaderLineType.Float,
					"Values from bigwig file: "+this.biwWigFile
					));
			}
		
		super.addMetaData(h2);
		w.writeHeader(h2);
		final SAMSequenceDictionaryProgress progress=new SAMSequenceDictionaryProgress(header);
		
		final List<Float> values=new ArrayList<Float>();
		while(r.hasNext())
			{
			final VariantContext ctx = progress.watch(r.next());
			values.clear();
			final String variantChrom= this.variantChromNameConverter.apply(ctx.getContig());
			
			final BigWigIterator iter=this.bbFileReader.getBigWigIterator(
					variantChrom,
					ctx.getStart()-1,
					variantChrom,
					ctx.getEnd(),
					this.contained
					);
			while(iter!=null && iter.hasNext())
				{
				final WigItem item=iter.next();
				final float v=item.getWigValue();
				values.add(v);
				if(this.aggregateMethod.equals(AggregateMethod.first)) break;
				}
			
			if(values.isEmpty())
				{
				w.add(ctx);
				continue;
				}
			final VariantContextBuilder b=new VariantContextBuilder(ctx);

			switch(this.aggregateMethod)
				{
				case all:
					b.attribute(this.TAG,values);
					break;
				case avg:
					double total=0L;
					for(final Float f:values) total+=f;
					b.attribute(this.TAG,(float)(total/values.size()));
					break;
				case first:
					b.attribute(this.TAG,values.get(0));
					break;
				case median:
					final double median_value;
					values.sort((A,B)->A.compareTo(B));
					final int mid_x= values.size()/2;
					if(values.size()==1)
						{
						median_value = values.get(0);
						}
					else if(values.size()%2==0)
                        {
                		median_value =  (values.get(mid_x-1)+values.get(mid_x))/2.0;
                        }
	                else
                        {
                		median_value =  values.get(mid_x);
                        }
					b.attribute(this.TAG,median_value);
					break;
				default: throw new IllegalStateException();
				}
			w.add(b.make());
			}
		progress.finish();
		return RETURN_OK;
		} catch(Exception err) {
			LOG.error(err);
			return -1;
		}
		}
	
	@Override
	public int doWork(List<String> args) {
		
		if(this.convertChrName==null || this.convertChrName.equals("identity"))
		{
		this.variantChromNameConverter= S -> S;
		}
	else if(this.convertChrName.equals("ensembl2ucsc"))
		{
		this.variantChromNameConverter= S -> {
			String s=S;
			if(!s.toLowerCase().startsWith("chr")) s="chr"+s;
			if(s.equals("chrMT")) s="M";
			return s;
			};
		}
	else
		{
		LOG.info("Undefined chromosome name converter type:"+this.convertChrName);
		return -1;
		}
	
	if(this.biwWigFile==null || this.biwWigFile.isEmpty())
		{
		LOG.info("Undefined BigWig file ");
		return -1;
		}
	try
		{
		
		this.bbFileReader= new BBFileReader(this.biwWigFile);
		if(!this.bbFileReader.isBigWigFile())
			{
			this.bbFileReader=null;
			throw new IOException(this.biwWigFile+" is not a bigWIG file.");
			}

		if(this.TAG==null || this.TAG.isEmpty())
			{
			this.TAG=this.biwWigFile;
			int i=TAG.lastIndexOf(File.separator);
			if(i!=-1) TAG=TAG.substring(i+1);
			i=this.TAG.indexOf('.');
			this.TAG=this.TAG.substring(0,i);
			LOG.info("setting tag to "+this.TAG);
			}
		
		}
	catch(final Exception err)
		{
		LOG.error(err);
		return -1;
		}

		
		try 
			{
			return doVcfToVcf(args, outputFile);
			}
		catch(Exception err) {
			LOG.error(err);
			return -1;
			}
		finally
			{
			try
				{
				if(this.bbFileReader!=null)
					{
					CloserUtil.close(this.bbFileReader.getBBFis());
					}
				CloserUtil.close(this.bbFileReader);
				this.bbFileReader=null;
				}
			catch(final Exception err)
				{
				LOG.error("Error",err);
				}
			}
		}
	
	public static void main(final String[] args) throws IOException
		{
		new VCFBigWig().instanceMain(args);
		}
}
