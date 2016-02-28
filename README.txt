Create a service:

```scala
class PriceSourceService(id: String) extends StatelessServiceActor(id) {
}
```

Map subject subscription to a topic:

```scala
onSubjectMapping {
    case Subject(_, TopicKey(instrument), _) => instrument
}
```

Define topic lifecycle hooks:

```scala
onStreamActive {
  case SimpleStreamId(instrument) => instruments += instrument
}
```

```scala
onStreamPassive {
  case SimpleStreamId(instrument) => instruments -= instrument
}
```

Publish data:

```scala
instrument !# ("price" -> getPrice)
```
