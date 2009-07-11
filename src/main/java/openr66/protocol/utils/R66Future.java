/**
 * Copyright 2009, Frederic Bregier, and individual contributors by the @author
 * tags. See the COPYRIGHT.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3.0 of the License, or (at your option)
 * any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this software; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA, or see the FSF
 * site: http://www.fsf.org.
 */
package openr66.protocol.utils;

import goldengate.common.future.GgFuture;

/**
 * @author Frederic Bregier
 *
 */
public class R66Future extends GgFuture {

    private Object result = null;

    /**
     *
     */
    public R66Future() {
    }

    /**
     * @param cancellable
     */
    public R66Future(boolean cancellable) {
        super(cancellable);
    }

    /**
     * @return the result
     */
    public Object getResult() {
        return result;
    }

    /**
     * @param result
     *            the result to set
     */
    public void setResult(Object result) {
        this.result = result;
    }

    public String toString() {
        return "Future: "+this.isDone()+" "+this.isSuccess()+" "+(this.getCause() != null ? this.getCause().getMessage() :"no cause")+
        " "+(this.result != null ? this.result.toString():"no result");
    }
}
