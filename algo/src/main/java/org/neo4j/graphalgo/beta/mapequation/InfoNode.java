package org.neo4j.graphalgo.beta.mapequation;

import java.util.Set;
import java.util.TreeSet;

public class InfoNode {
    private Set<Integer> nodes = new TreeSet<>();
    private double flow = 0.0;
    private double enterFlow = 0.0;
    private double exitFlow = 0.0;

    public InfoNode()
    {
    }

    public Set<Integer> nodes()
    {
        return this.nodes;
    }

    public double flow()
    {
        return this.flow;
    }

    public double enterFlow()
    {
        return this.enterFlow;
    }

    public double exitFlow()
    {
        return this.exitFlow;
    }

    public double enterLogEnter()
    {
        return Math.abs(this.enterFlow) < Math.ulp(1.0D)
             ? 0.0
             : this.enterFlow * Math.log(this.enterFlow) / Math.log(2)
             ;
    }

    public double exitLogExit()
    {
        return Math.abs(this.exitFlow) < Math.ulp(1.0D)
             ? 0.0
             : this.exitFlow * Math.log(this.exitFlow) / Math.log(2)
             ;
    }

    public double flowLogFlow()
    {
        double f = this.exitFlow + this.flow;
        return Math.abs(f) < Math.ulp(1.0D)
             ? 0.0
             : f * Math.log(f) / Math.log(2);
    }

    public void addNode(int nodeId)
    {
        this.nodes.add(nodeId);
    }

    public void addFlow(double flow)
    {
        this.flow += flow;
    }

    public void addEnterFlow(double enterFlow)
    {
        this.enterFlow += enterFlow;
    }

    public void addExitFlow(double exitFlow)
    {
        this.exitFlow += exitFlow;
    }

    public InfoNode merge(InfoNode other)
    {
        InfoNode res = new InfoNode();

        res.nodes.addAll(this.nodes);
        res.nodes.addAll(other.nodes);

        res.addFlow(this.flow + other.flow);
        res.addEnterFlow(this.enterFlow + other.enterFlow);
        res.addExitFlow(this.exitFlow + other.exitFlow);

        return res;
    }

    public InfoNode remove(InfoNode other)
    {
        InfoNode res = new InfoNode();

        res.nodes.addAll(this.nodes);
        res.nodes.removeAll(other.nodes);

        res.addFlow(this.flow + other.flow);
        res.addEnterFlow(this.enterFlow + other.enterFlow);
        res.addExitFlow(this.exitFlow + other.exitFlow);

        return res;
    }
}
